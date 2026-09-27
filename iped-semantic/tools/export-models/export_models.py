#!/usr/bin/env python3
"""Reproducible export of the IPED semantic search models to self-contained ONNX files.

Each exported model takes raw input (UTF-8 text, or pixels for the visual model) and returns the
L2-normalised embedding. For text, the tokenizer runs *inside* the graph through ONNX Runtime
Extensions, so what prepares a case during processing and what embeds a question in the analysis
UI (or in the reference remote server) are literally the same file (research D2, D3).

Usage:
    python export_models.py text  --out <dir>      # intfloat/multilingual-e5-small, int8
    python export_models.py visual --out <dir>     # clip-ViT-B-32-multilingual-v1 (see README)

The text export is fully pinned: model revision, exporter package versions (requirements.txt) and
quantization settings. Running it twice with the same requirements produces the same model.onnx,
and therefore the same sha256 in model.json, which is the identity a case records.
"""

import argparse
import datetime
import hashlib
import json
import os
import shutil
import sys
import zipfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

# ---------------------------------------------------------------------------------------------
# Pinned sources
# ---------------------------------------------------------------------------------------------

TEXT_REPO = "intfloat/multilingual-e5-small"
TEXT_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
TEXT_VARIANT = "onnx-int8-ortx"
TEXT_DIR_NAME = "multilingual-e5-small-int8"
TEXT_MAX_TOKENS = 512
TEXT_DIMENSIONS = 384

# XLM-RoBERTa special ids, as the fairseq-mode SentencePiece tokenizer emits them.
XLMR_PAD_ID = 1
XLMR_EOS_ID = 2

# Version of the fingerprint probe set shipped in iped-semantic (research D10). Bumped only when
# the probe texts or images change.
PROBES_VERSION = 1

# Opset of the text encoder as published; every node the export adds is written for this opset.
TEXT_ENCODER_OPSET = 11
CONTRIB_DOMAIN = "ai.onnx.contrib"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def package_versions():
    import onnxruntime
    import onnxruntime_extensions
    import transformers
    return {
        "python": sys.version.split()[0],
        "onnx": onnx.__version__,
        "onnxruntime": onnxruntime.__version__,
        "onnxruntime-extensions": onnxruntime_extensions.__version__,
        "transformers": transformers.__version__,
    }


# ---------------------------------------------------------------------------------------------
# Text: tokenizer graph + padding glue + int8 encoder + mean pooling + L2 norm
# ---------------------------------------------------------------------------------------------

def load_text_sources():
    from huggingface_hub import hf_hub_download
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(TEXT_REPO, revision=TEXT_REVISION, use_fast=False)
    encoder_path = hf_hub_download(TEXT_REPO, "onnx/model.onnx", revision=TEXT_REVISION)
    return tokenizer, encoder_path


def tokenizer_nodes(tokenizer):
    """The SentencepieceTokenizer node(s) ONNX Runtime Extensions generates for the tokenizer,
    with the graph input renamed to 'text'. Returns (nodes, initializers, outputs)."""
    from onnxruntime_extensions import gen_processing_models

    pre, _ = gen_processing_models(tokenizer, pre_kwargs={})
    if [n.op_type for n in pre.graph.node] != ["SentencepieceTokenizer"]:
        raise RuntimeError("unexpected tokenizer graph: %s" % [n.op_type for n in pre.graph.node])
    source_input = pre.graph.input[0].name
    nodes = []
    for node in pre.graph.node:
        n = onnx.NodeProto()
        n.CopyFrom(node)
        for i, name in enumerate(n.input):
            if name == source_input:
                n.input[i] = "text"
        nodes.append(n)
    outputs = [o.name for o in pre.graph.output]
    if outputs[:2] != ["tokens", "instance_indices"]:
        raise RuntimeError("unexpected tokenizer outputs: %s" % outputs)
    return nodes, list(pre.graph.initializer), outputs


def padding_glue_nodes():
    """Ragged token ids -> padded input_ids / attention_mask / token_type_ids, [N, L] int64.

    Rows longer than TEXT_MAX_TOKENS are truncated as the reference tokenizer truncates them: the
    first TEXT_MAX_TOKENS - 1 ids, then </s>. Written for opset 11 (axes are attributes)."""
    c = []

    def const(name, values, dtype=np.int64):
        c.append(helper.make_node("Constant", [], [name],
                                  value=numpy_helper.from_array(np.array(values, dtype=dtype), name)))

    const("iped_zero_1d", [0])
    const("iped_one_1d", [1])
    const("iped_minus1_1d", [-1])
    const("iped_intmax_1d", [np.iinfo(np.int64).max])
    const("iped_zero", 0)
    const("iped_one", 1)
    const("iped_max_tokens", TEXT_MAX_TOKENS)
    const("iped_max_tokens_1d", [TEXT_MAX_TOKENS])
    const("iped_last_pos", TEXT_MAX_TOKENS - 1)
    const("iped_pad_id", XLMR_PAD_ID)
    const("iped_eos_id", XLMR_EOS_ID)

    n = helper.make_node

    def int_min(a, b, out):
        # Min accepts integers only from opset 12; the encoder is opset 11.
        return [n("Less", [a, b], [out + "_a_smaller"]),
                n("Where", [out + "_a_smaller", a, b], [out])]

    nodes = c + [
        n("Cast", ["tokens"], ["iped_tokens64"], to=TensorProto.INT64),
        n("Slice", ["instance_indices", "iped_zero_1d", "iped_minus1_1d", "iped_zero_1d"], ["iped_starts"]),
        n("Slice", ["instance_indices", "iped_one_1d", "iped_intmax_1d", "iped_zero_1d"], ["iped_ends"]),
        n("Sub", ["iped_ends", "iped_starts"], ["iped_lens"]),
        n("ReduceMax", ["iped_lens"], ["iped_maxlen_1d"], axes=[0], keepdims=1),
    ] + int_min("iped_maxlen_1d", "iped_max_tokens_1d", "iped_width_1d") + [
        n("Squeeze", ["iped_width_1d"], ["iped_width"], axes=[0]),
        n("Range", ["iped_zero", "iped_width", "iped_one"], ["iped_pos"]),
        n("Unsqueeze", ["iped_pos"], ["iped_pos_row"], axes=[0]),          # [1, L]
        n("Unsqueeze", ["iped_starts"], ["iped_starts_col"], axes=[1]),    # [N, 1]
        n("Unsqueeze", ["iped_lens"], ["iped_lens_col"], axes=[1]),        # [N, 1]
        n("Add", ["iped_starts_col", "iped_pos_row"], ["iped_index"]),     # [N, L]
    ] + int_min("iped_lens_col", "iped_max_tokens", "iped_kept") + [   # [N, 1]
        n("Less", ["iped_pos_row", "iped_kept"], ["iped_valid"]),          # [N, L]
        n("Shape", ["iped_tokens64"], ["iped_total_1d"]),
        n("Sub", ["iped_total_1d", "iped_one_1d"], ["iped_last_index_1d"]),
    ] + int_min("iped_index", "iped_last_index_1d", "iped_index_clipped") + [
        n("Gather", ["iped_tokens64", "iped_index_clipped"], ["iped_gathered"], axis=0),
        n("Where", ["iped_valid", "iped_gathered", "iped_pad_id"], ["iped_ids_untruncated"]),
        n("Greater", ["iped_lens_col", "iped_max_tokens"], ["iped_truncated_row"]),
        n("Equal", ["iped_pos_row", "iped_last_pos"], ["iped_last_col"]),
        n("And", ["iped_truncated_row", "iped_last_col"], ["iped_put_eos"]),
        n("Where", ["iped_put_eos", "iped_eos_id", "iped_ids_untruncated"], ["input_ids"]),
        n("Cast", ["iped_valid"], ["attention_mask"], to=TensorProto.INT64),
        n("Mul", ["input_ids", "iped_zero"], ["token_type_ids"]),
    ]
    return nodes


def pooling_nodes(hidden="last_hidden_state"):
    """Masked mean pooling over tokens followed by L2 normalisation (the e5 recipe)."""
    n = helper.make_node
    eps = numpy_helper.from_array(np.array(1e-9, dtype=np.float32), "iped_eps")
    return [
        n("Constant", [], ["iped_eps"], value=eps),
        n("Cast", ["attention_mask"], ["iped_mask_f"], to=TensorProto.FLOAT),
        n("Unsqueeze", ["iped_mask_f"], ["iped_mask_3d"], axes=[2]),
        n("Mul", [hidden, "iped_mask_3d"], ["iped_masked"]),
        n("ReduceSum", ["iped_masked"], ["iped_summed"], axes=[1], keepdims=0),
        n("ReduceSum", ["iped_mask_3d"], ["iped_count"], axes=[1], keepdims=0),
        n("Max", ["iped_count", "iped_eps"], ["iped_count_safe"]),
        n("Div", ["iped_summed", "iped_count_safe"], ["iped_mean"]),
        n("LpNormalization", ["iped_mean"], ["embedding"], axis=1, p=2),
    ]


def text_value_info_in():
    return helper.make_tensor_value_info("text", TensorProto.STRING, ["N"])


def build_prep_model(tokenizer):
    """Tokenizer + glue only; used to verify the ids against the reference tokenizer."""
    tok_nodes, tok_inits, _ = tokenizer_nodes(tokenizer)
    outputs = [helper.make_tensor_value_info(name, TensorProto.INT64, ["N", "L"])
               for name in ("input_ids", "attention_mask")]
    graph = helper.make_graph(tok_nodes + padding_glue_nodes(), "iped_text_prep", [text_value_info_in()],
                              outputs, initializer=tok_inits)
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", TEXT_ENCODER_OPSET),
                                                   helper.make_opsetid(CONTRIB_DOMAIN, 1)],
                             ir_version=8, producer_name="iped-semantic export_models.py")


def build_text_model(tokenizer, encoder):
    tok_nodes, tok_inits, _ = tokenizer_nodes(tokenizer)
    enc_inputs = [i.name for i in encoder.graph.input]
    if enc_inputs != ["input_ids", "attention_mask", "token_type_ids"]:
        raise RuntimeError("unexpected encoder inputs: %s" % enc_inputs)
    if encoder.graph.output[0].name != "last_hidden_state":
        raise RuntimeError("unexpected encoder output: %s" % encoder.graph.output[0].name)
    enc_opsets = {o.domain or "": o.version for o in encoder.opset_import}
    nodes = tok_nodes + padding_glue_nodes() + list(encoder.graph.node) + pooling_nodes()
    initializers = tok_inits + list(encoder.graph.initializer)
    names = [o for node in nodes for o in node.output]
    if len(names) != len(set(names)):
        raise RuntimeError("tensor name collision while composing the text model")
    output = helper.make_tensor_value_info("embedding", TensorProto.FLOAT, ["N", TEXT_DIMENSIONS])
    graph = helper.make_graph(nodes, "iped_text_embedder", [text_value_info_in()], [output],
                              initializer=initializers)
    opsets = [helper.make_opsetid(d, v) for d, v in sorted(enc_opsets.items())]
    opsets.append(helper.make_opsetid(CONTRIB_DOMAIN, 1))
    return helper.make_model(graph, opset_imports=opsets, ir_version=8,
                             producer_name="iped-semantic export_models.py")


def quantize_encoder(src, dst):
    """Dynamic int8 quantization with unsigned weights (U8U8), per output channel. Unsigned
    activations and weights keep the integer matmuls exact on CPUs without VNNI, where U8S8 can
    saturate; that is what lets the fingerprint tolerance absorb only float-level differences
    between machines. Per-channel scales were chosen over per-tensor on 2026-09-26: same size
    (118 MB), cosine to fp32 of 0.995-0.998 on natural text against 0.988-0.996."""
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(src, dst, weight_type=QuantType.QUInt8, per_channel=True, reduce_range=False)


def ort_session(model_bytes_or_path):
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())
    so.intra_op_num_threads = 1
    return ort.InferenceSession(model_bytes_or_path, so, providers=["CPUExecutionProvider"])


VERIFY_TEXTS = [
    "query: quanto?",
    "passage: fechado, amanhã na portaria",
    "passage: deixa o pacote com o moleque da portaria, o acerto é amanhã",
    "query: entrega de mercadoria e pagamento combinado",
    "passage: The shipment will be left at the gate; payment tomorrow.",
    "passage: La entrega será mañana en la portería.",
    "",
    "passage: " + " ".join(["ação"] * 900),  # well over 512 tokens: exercises truncation
]


def verify_text(tokenizer, prep_model, encoder_fp32_path, model_path):
    """Checks that the in-graph ids equal the reference tokenizer's, and reports how close the
    int8 embedding is to the fp32 reference. Raises on id mismatch."""
    prep = ort_session(prep_model.SerializeToString())
    ids, mask = prep.run(["input_ids", "attention_mask"], {"text": np.array(VERIFY_TEXTS, dtype=object)})
    ref = tokenizer(VERIFY_TEXTS, padding=True, truncation=True, max_length=TEXT_MAX_TOKENS, return_tensors="np")
    if ids.shape != ref["input_ids"].shape or not np.array_equal(ids, ref["input_ids"]):
        raise RuntimeError("in-graph token ids differ from the reference tokenizer")
    if not np.array_equal(mask, ref["attention_mask"]):
        raise RuntimeError("in-graph attention mask differs from the reference tokenizer")

    import onnxruntime as ort
    enc = ort.InferenceSession(encoder_fp32_path, providers=["CPUExecutionProvider"])
    hidden = enc.run(None, {"input_ids": ref["input_ids"].astype(np.int64),
                            "attention_mask": ref["attention_mask"].astype(np.int64),
                            "token_type_ids": np.zeros_like(ref["input_ids"], dtype=np.int64)})[0]
    m = ref["attention_mask"][..., None].astype(np.float32)
    mean = (hidden * m).sum(1) / np.maximum(m.sum(1), 1e-9)
    reference = mean / np.linalg.norm(mean, axis=1, keepdims=True)

    full = ort_session(model_path)
    emb = full.run(["embedding"], {"text": np.array(VERIFY_TEXTS, dtype=object)})[0]
    cos = (emb * reference).sum(1)
    norms = np.linalg.norm(emb, axis=1)
    per_text = {("%d:%s" % (i, t[:40])): round(float(c), 4) for i, (t, c) in enumerate(zip(VERIFY_TEXTS, cos))}
    return {"ids_equal_reference": True, "cosine_int8_vs_fp32": per_text,
            "max_norm_deviation": float(np.abs(norms - 1).max())}


MIT_NOTICE = """The model in this directory is a converted and quantized copy of
{repo} at revision {rev}
(https://huggingface.co/{repo}), whose model card declares "license: mit".
The SentencePiece vocabulary embedded in the tokenizer node comes from the same repository.

Conversion (tokenizer graph, int8 quantization, pooling) was done by
iped-semantic/tools/export-models/export_models.py; it changes the numeric format of the weights,
not their origin or license.

MIT License

Copyright (c) the authors of {repo}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""


def export_text(out_dir, work_dir):
    tokenizer, encoder_fp32 = load_text_sources()
    os.makedirs(work_dir, exist_ok=True)
    encoder_int8 = os.path.join(work_dir, "encoder-int8.onnx")
    quantize_encoder(encoder_fp32, encoder_int8)

    model = build_text_model(tokenizer, onnx.load(encoder_int8))
    onnx.checker.check_model(model)
    target = os.path.join(out_dir, "models", "semantic", "text", TEXT_DIR_NAME)
    os.makedirs(target, exist_ok=True)
    model_path = os.path.join(target, "model.onnx")
    onnx.save(model, model_path)

    report = verify_text(tokenizer, build_prep_model(tokenizer), encoder_fp32, model_path)
    sha = sha256_of(model_path)
    meta = {
        "id": "%s@%s+%s" % (TEXT_REPO, TEXT_REVISION, TEXT_VARIANT),
        "sha256": sha,
        "modalities": ["text"],
        "dimensions": TEXT_DIMENSIONS,
        "similarity": "DOT_PRODUCT",
        "normalized": True,
        "queryPrefix": "query: ",
        "passagePrefix": "passage: ",
        "maxTokens": TEXT_MAX_TOKENS,
        "textInput": "text",
        "output": "embedding",
        "probesVersion": PROBES_VERSION,
        "source": {"repository": TEXT_REPO, "revision": TEXT_REVISION, "file": "onnx/model.onnx",
                   "license": "MIT (model card)"},
        "export": {"tool": "iped-semantic/tools/export-models/export_models.py",
                   "quantization": "dynamic, weights QUInt8, per_channel=true, reduce_range=false",
                   "versions": package_versions(),
                   "verification": report},
    }
    with open(os.path.join(target, "model.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(target, "LICENSE"), "w", encoding="utf-8", newline="\n") as f:
        f.write(MIT_NOTICE.format(repo=TEXT_REPO, rev=TEXT_REVISION))

    zip_path = os.path.join(out_dir, "semantic-text-%s.zip" % TEXT_DIR_NAME)
    make_zip(target, zip_path, root=TEXT_DIR_NAME)
    print(json.dumps({"model": model_path, "sha256": sha, "zip": zip_path, "verification": report}, indent=2))


def make_zip(src_dir, zip_path, root):
    """Deterministic zip: sorted entries, fixed timestamps."""
    entries = []
    for base, _, files in os.walk(src_dir):
        for name in files:
            full = os.path.join(base, name)
            rel = os.path.relpath(full, src_dir).replace(os.sep, "/")
            entries.append((root + "/" + rel, full))
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for arc, full in sorted(entries):
            info = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as f:
                z.writestr(info, f.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("what", choices=["text", "visual"])
    parser.add_argument("--out", required=True, help="output directory (receives models/semantic/...)")
    parser.add_argument("--work", help="scratch directory for intermediate files (default: <out>/work)")
    args = parser.parse_args()
    work = args.work or os.path.join(args.out, "work")
    if args.what == "text":
        export_text(args.out, work)
    else:
        from export_visual import export_visual  # separate module: needs torch, see README
        export_visual(args.out, work)


if __name__ == "__main__":
    main()
