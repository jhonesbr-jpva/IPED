"""Export of the visual model (T075): the multilingual CLIP text encoder with its WordPiece tokenizer
inside the graph, and the CLIP ViT-B/32 image encoder it was distilled against.

    python export_models.py visual --out <dir>

Two graphs, one model identity:

* text.onnx  - UTF-8 text -> 512-d L2-normalised embedding. BertTokenizer (ONNX Runtime
  Extensions) + truncation to 128 tokens + DistilBERT (the repository's onnx/model.onnx) + mean
  pooling + the Dense 768->512 projection + L2 normalisation: what SentenceTransformer.encode does
  for sentence-transformers/clip-ViT-B-32-multilingual-v1.
* image.onnx - pixel_values [N, 3, 224, 224] -> 512-d L2-normalised embedding: CLIP ViT-B/32
  vision tower and visual projection (openai/clip-vit-base-patch32), exported from torch.

The pixel pre-processing (shortest side to 224 with bicubic resampling, centre crop, scale to
[0, 1], CLIP mean/std) is declared in model.json and done by the caller: in IPED, by
ImagePreprocessor in Java2D. model.json also carries two probe images with their reference
embeddings, so an installation can check that its pre-processing reproduces this export.

Quantization is decided per graph by measurement, and recorded: dynamic int8 (QUInt8 weights,
per channel, as the text model) is kept only when every verification input stays at cosine
>= QUANT_MIN_COSINE from the fp32 reference; otherwise the graph stays fp32.

Needs torch (see requirements-visual.txt).
"""

import json
import os

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

import export_models as base

TEXT_REPO = "sentence-transformers/clip-ViT-B-32-multilingual-v1"
TEXT_REVISION = "58edf8cada9e398793dca955574a48cbb7f18be2"
IMAGE_REPO = "openai/clip-vit-base-patch32"
IMAGE_REVISION = "3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268"
VISUAL_DIR_NAME = "clip-vit-b-32-multilingual-v1"
DIMENSIONS = 512
MAX_TOKENS = 128          # sentence_bert_config.json: max_seq_length
BERT_SEP_ID = 102
TEXT_OPSET = 11           # opset of the repository's DistilBERT graph
IMAGE_OPSET = 17
IMAGE_SIZE = 224
CLIP_MEAN = [0.48145466, 0.4578275, 0.40821073]
CLIP_STD = [0.26862954, 0.26130258, 0.27577711]
QUANT_MIN_COSINE = 0.99

VERIFY_TEXTS = [
    "uma praia ao pôr do sol",
    "a beach at sunset",
    "una playa al atardecer",
    "um carro vermelho estacionado na rua",
    "documento de identidade sobre a mesa",
    "pessoas reunidas em volta de uma mesa com dinheiro",
    "",
    " ".join(["palavra"] * 300),  # well over 128 tokens: exercises truncation
]


# ---------------------------------------------------------------------------------------------
# Text
# ---------------------------------------------------------------------------------------------

def load_text_sources():
    from huggingface_hub import hf_hub_download
    from safetensors.numpy import load_file
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(TEXT_REPO, revision=TEXT_REVISION, use_fast=False)
    encoder_path = hf_hub_download(TEXT_REPO, "onnx/model.onnx", revision=TEXT_REVISION)
    pooling = json.load(open(hf_hub_download(TEXT_REPO, "1_Pooling/config.json", revision=TEXT_REVISION)))
    dense_cfg = json.load(open(hf_hub_download(TEXT_REPO, "2_Dense/config.json", revision=TEXT_REVISION)))
    if not pooling.get("pooling_mode_mean_tokens") or pooling.get("pooling_mode_cls_token"):
        raise RuntimeError("unexpected pooling: %s" % pooling)
    if dense_cfg != {"in_features": 768, "out_features": DIMENSIONS, "bias": False,
                     "activation_function": "torch.nn.modules.linear.Identity"}:
        raise RuntimeError("unexpected dense layer: %s" % dense_cfg)
    weights = load_file(hf_hub_download(TEXT_REPO, "2_Dense/model.safetensors", revision=TEXT_REVISION))
    dense = weights["linear.weight"].astype(np.float32)  # [512, 768], torch Linear layout
    return tokenizer, encoder_path, dense


def tokenizer_nodes(tokenizer):
    """The BertTokenizer node ONNX Runtime Extensions generates, reading graph input 'text'."""
    from onnxruntime_extensions import gen_processing_models

    pre, _ = gen_processing_models(tokenizer, pre_kwargs={})
    if [n.op_type for n in pre.graph.node] != ["BertTokenizer"]:
        raise RuntimeError("unexpected tokenizer graph: %s" % [n.op_type for n in pre.graph.node])
    source_input = pre.graph.input[0].name
    node = onnx.NodeProto()
    node.CopyFrom(pre.graph.node[0])
    for i, name in enumerate(node.input):
        if name == source_input:
            node.input[i] = "text"
    # the outputs are renamed so the glue decides what the encoder sees
    renamed = {"input_ids": "iped_tok_ids", "token_type_ids": "iped_tok_types",
               "attention_mask": "iped_tok_mask", "offset_mapping": "iped_tok_offsets"}
    for i, name in enumerate(node.output):
        node.output[i] = renamed.get(name, "iped_tok_" + name)
    return [node], list(pre.graph.initializer)


def truncation_nodes():
    """[L] token ids of one text -> input_ids / attention_mask [1, min(L, 128)], int64.

    A text longer than MAX_TOKENS keeps its first MAX_TOKENS - 1 ids and ends with [SEP], as the
    reference tokenizer truncates. One text per run, which is how IPED calls the model (dynamic
    quantization makes batch composition affect the vectors)."""
    n = helper.make_node
    c = []

    def const(name, values):
        c.append(n("Constant", [], [name], value=numpy_helper.from_array(np.array(values, dtype=np.int64), name)))

    const("iped_v_zero_1d", [0])
    const("iped_v_keep_1d", [MAX_TOKENS - 1])
    const("iped_v_max_1d", [MAX_TOKENS])
    const("iped_v_sep_1d", [BERT_SEP_ID])
    return c + [
        n("Cast", ["iped_tok_ids"], ["iped_v_ids"], to=TensorProto.INT64),
        n("Reshape", ["iped_v_ids", "iped_v_minus1"], ["iped_v_flat"]),
        n("Shape", ["iped_v_flat"], ["iped_v_len_1d"]),
        n("Greater", ["iped_v_len_1d", "iped_v_max_1d"], ["iped_v_long_1d"]),
        n("Squeeze", ["iped_v_long_1d"], ["iped_v_long"], axes=[0]),
        n("Slice", ["iped_v_flat", "iped_v_zero_1d", "iped_v_keep_1d", "iped_v_zero_1d"], ["iped_v_head"]),
        n("Concat", ["iped_v_head", "iped_v_sep_1d"], ["iped_v_cut"], axis=0),
        n("If", ["iped_v_long"], ["iped_v_kept"],
          then_branch=_identity_branch("iped_v_cut", "then"),
          else_branch=_identity_branch("iped_v_flat", "else")),
        n("Unsqueeze", ["iped_v_kept"], ["input_ids"], axes=[0]),
        n("Shape", ["input_ids"], ["iped_v_shape"]),
        n("ConstantOfShape", ["iped_v_shape"], ["attention_mask"],
          value=numpy_helper.from_array(np.array([1], dtype=np.int64), "one")),
    ]


def _identity_branch(source, tag):
    out = helper.make_tensor_value_info("iped_v_branch_" + tag, TensorProto.INT64, ["L"])
    return helper.make_graph([helper.make_node("Identity", [source], ["iped_v_branch_" + tag])],
                             "iped_v_" + tag, [], [out])


def pooling_dense_nodes(dense):
    """Masked mean pooling, the Dense 768->512 projection (no bias, identity activation), L2."""
    n = helper.make_node
    eps = numpy_helper.from_array(np.array(1e-9, dtype=np.float32), "iped_v_eps")
    return [
        n("Constant", [], ["iped_v_eps"], value=eps),
        n("Constant", [], ["iped_v_dense_t"], value=numpy_helper.from_array(dense.T.copy(), "iped_v_dense_t")),
        n("Cast", ["attention_mask"], ["iped_v_mask_f"], to=TensorProto.FLOAT),
        n("Unsqueeze", ["iped_v_mask_f"], ["iped_v_mask_3d"], axes=[2]),
        n("Mul", ["last_hidden_state", "iped_v_mask_3d"], ["iped_v_masked"]),
        n("ReduceSum", ["iped_v_masked"], ["iped_v_summed"], axes=[1], keepdims=0),
        n("ReduceSum", ["iped_v_mask_3d"], ["iped_v_count"], axes=[1], keepdims=0),
        n("Max", ["iped_v_count", "iped_v_eps"], ["iped_v_count_safe"]),
        n("Div", ["iped_v_summed", "iped_v_count_safe"], ["iped_v_mean"]),
        n("MatMul", ["iped_v_mean", "iped_v_dense_t"], ["iped_v_projected"]),
        n("LpNormalization", ["iped_v_projected"], ["embedding"], axis=1, p=2),
    ]


def build_text_model(tokenizer, encoder, dense):
    tok_nodes, tok_inits = tokenizer_nodes(tokenizer)
    enc_inputs = [i.name for i in encoder.graph.input]
    if enc_inputs != ["input_ids", "attention_mask"]:
        raise RuntimeError("unexpected encoder inputs: %s" % enc_inputs)
    if encoder.graph.output[0].name != "last_hidden_state":
        raise RuntimeError("unexpected encoder output: %s" % encoder.graph.output[0].name)
    minus1 = helper.make_node("Constant", [], ["iped_v_minus1"],
                              value=numpy_helper.from_array(np.array([-1], dtype=np.int64), "iped_v_minus1"))
    nodes = tok_nodes + [minus1] + truncation_nodes() + list(encoder.graph.node) + pooling_dense_nodes(dense)
    names = [o for node in nodes for o in node.output]
    if len(names) != len(set(names)):
        raise RuntimeError("tensor name collision while composing the visual text model")
    text_in = helper.make_tensor_value_info("text", TensorProto.STRING, [1])
    output = helper.make_tensor_value_info("embedding", TensorProto.FLOAT, [1, DIMENSIONS])
    graph = helper.make_graph(nodes, "iped_visual_text_embedder", [text_in], [output],
                              initializer=tok_inits + list(encoder.graph.initializer))
    opsets = [helper.make_opsetid(o.domain or "", o.version) for o in encoder.opset_import]
    opsets.append(helper.make_opsetid(base.CONTRIB_DOMAIN, 1))
    return helper.make_model(graph, opset_imports=opsets, ir_version=8,
                             producer_name="iped-semantic export_visual.py")


def text_reference(texts):
    """SentenceTransformer.encode, normalised: what the composed graph must reproduce."""
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(TEXT_REPO, revision=TEXT_REVISION, device="cpu")
    return model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)


def run_text(model_path, texts):
    session = base.ort_session(model_path)
    return np.stack([session.run(["embedding"], {"text": np.array([t], dtype=object)})[0][0] for t in texts])


def check_ids(tokenizer, model_path):
    """The in-graph ids must equal the reference tokenizer's, truncation included."""
    probe = onnx.load(model_path)
    probe.graph.output.append(helper.make_tensor_value_info("input_ids", TensorProto.INT64, [1, "L"]))
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())
    session = ort.InferenceSession(probe.SerializeToString(), so, providers=["CPUExecutionProvider"])
    for t in VERIFY_TEXTS:
        ids = session.run(["input_ids"], {"text": np.array([t], dtype=object)})[0][0]
        ref = tokenizer(t, truncation=True, max_length=MAX_TOKENS)["input_ids"]
        if list(ids) != list(ref):
            raise RuntimeError("in-graph ids differ from the reference for %r: %s vs %s" % (t[:40], list(ids)[:12],
                                                                                            ref[:12]))


def export_text_graph(work_dir, target):
    tokenizer, encoder_fp32, dense = load_text_sources()
    reference = text_reference(VERIFY_TEXTS)

    fp32_path = os.path.join(work_dir, "visual-text-fp32.onnx")
    model = build_text_model(tokenizer, onnx.load(encoder_fp32), dense)
    onnx.save(model, fp32_path, save_as_external_data=False)
    check_ids(tokenizer, fp32_path)
    cos_fp32 = (run_text(fp32_path, VERIFY_TEXTS) * reference).sum(1)
    if cos_fp32.min() < 0.9999:
        raise RuntimeError("the fp32 text graph does not reproduce the reference: %s" % cos_fp32)

    encoder_int8 = os.path.join(work_dir, "visual-text-encoder-int8.onnx")
    base.quantize_encoder(encoder_fp32, encoder_int8)
    int8_path = os.path.join(work_dir, "visual-text-int8.onnx")
    onnx.save(build_text_model(tokenizer, onnx.load(encoder_int8), dense), int8_path)
    cos_int8 = (run_text(int8_path, VERIFY_TEXTS) * reference).sum(1)

    quantized = bool(cos_int8.min() >= QUANT_MIN_COSINE)
    final = os.path.join(target, "text.onnx")
    onnx.save(onnx.load(int8_path if quantized else fp32_path), final)
    report = {
        "ids_equal_reference": True,
        "cosine_fp32_vs_reference": {("%d:%s" % (i, t[:30])): round(float(c), 5)
                                     for i, (t, c) in enumerate(zip(VERIFY_TEXTS, cos_fp32))},
        "cosine_int8_vs_reference": {("%d:%s" % (i, t[:30])): round(float(c), 4)
                                     for i, (t, c) in enumerate(zip(VERIFY_TEXTS, cos_int8))},
        "quantized": quantized,
    }
    return final, report


# ---------------------------------------------------------------------------------------------
# Image
# ---------------------------------------------------------------------------------------------

def probe_images():
    """Deterministic synthetic pictures: nothing downloaded, nothing from any evidence."""
    from PIL import Image, ImageDraw
    images = []
    a = Image.new("RGB", (320, 240), (30, 110, 200))           # sky over sand, with a sun
    d = ImageDraw.Draw(a)
    d.rectangle([0, 150, 320, 240], fill=(220, 200, 150))
    d.ellipse([220, 40, 280, 100], fill=(250, 220, 60))
    images.append(("probe-1.png", a))
    b = Image.new("RGB", (200, 300), (245, 245, 245))          # a red square on white, portrait
    d = ImageDraw.Draw(b)
    d.rectangle([50, 100, 150, 200], fill=(210, 20, 20))
    images.append(("probe-2.png", b))
    rng = np.random.RandomState(7)
    c = Image.fromarray(rng.randint(0, 256, (224, 224, 3), dtype=np.uint8))   # noise
    images.append(("probe-3.png", c))
    return images


def image_reference(images):
    import torch
    from transformers import CLIPImageProcessor, CLIPModel
    processor = CLIPImageProcessor.from_pretrained(IMAGE_REPO, revision=IMAGE_REVISION)
    model = CLIPModel.from_pretrained(IMAGE_REPO, revision=IMAGE_REVISION).eval()
    pixels = processor(images=images, return_tensors="pt")["pixel_values"]
    with torch.no_grad():
        feats = model.get_image_features(pixel_values=pixels)
    feats = torch.nn.functional.normalize(feats, dim=-1)
    return pixels.numpy(), feats.numpy()


def export_image_fp32(path):
    import torch
    from transformers import CLIPVisionModelWithProjection

    vision = CLIPVisionModelWithProjection.from_pretrained(IMAGE_REPO, revision=IMAGE_REVISION).eval()

    class Normalised(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, pixel_values):
            return torch.nn.functional.normalize(self.inner(pixel_values=pixel_values).image_embeds, dim=-1)

    dummy = torch.zeros(1, 3, IMAGE_SIZE, IMAGE_SIZE)
    with torch.no_grad():
        torch.onnx.export(Normalised(vision), (dummy,), path, input_names=["pixel_values"],
                          output_names=["image_embedding"],
                          dynamic_axes={"pixel_values": {0: "N"}, "image_embedding": {0: "N"}},
                          opset_version=IMAGE_OPSET, do_constant_folding=True)


def run_image(path, pixels):
    import onnxruntime as ort
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    return np.stack([session.run(["image_embedding"], {"pixel_values": pixels[i:i + 1]})[0][0]
                     for i in range(len(pixels))])


def export_image_graph(work_dir, target):
    names_images = probe_images()
    pixels, reference = image_reference([im for _, im in names_images])

    fp32_path = os.path.join(work_dir, "visual-image-fp32.onnx")
    export_image_fp32(fp32_path)
    cos_fp32 = (run_image(fp32_path, pixels) * reference).sum(1)
    if cos_fp32.min() < 0.9999:
        raise RuntimeError("the fp32 image graph does not reproduce the reference: %s" % cos_fp32)

    int8_path = os.path.join(work_dir, "visual-image-int8.onnx")
    base.quantize_encoder(fp32_path, int8_path)
    cos_int8 = (run_image(int8_path, pixels) * reference).sum(1)

    quantized = bool(cos_int8.min() >= QUANT_MIN_COSINE)
    final = os.path.join(target, "image.onnx")
    onnx.save(onnx.load(int8_path if quantized else fp32_path), final)

    # probes for the installation: pictures and the embeddings this export gives them
    os.makedirs(os.path.join(target, "verify"), exist_ok=True)
    produced = run_image(final, pixels)
    probes = []
    for (name, im), vec in zip(names_images, produced):
        im.save(os.path.join(target, "verify", name), format="PNG")
        probes.append({"file": "verify/" + name, "embedding": [round(float(x), 6) for x in vec]})
    report = {
        "cosine_fp32_vs_reference": [round(float(c), 5) for c in cos_fp32],
        "cosine_int8_vs_reference": [round(float(c), 4) for c in cos_int8],
        "quantized": quantized,
    }
    return final, report, probes


# ---------------------------------------------------------------------------------------------

LICENSE_TEXT = """This directory holds converted copies of two models.

text.onnx - {text_repo} at revision {text_rev}
  (https://huggingface.co/{text_repo}), whose model card declares "license: apache-2.0".
  The WordPiece vocabulary in the tokenizer node comes from the same repository.
  Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0

image.onnx - {image_repo} at revision {image_rev}
  (https://huggingface.co/{image_repo}), the CLIP ViT-B/32 image encoder released by OpenAI
  under the MIT License (https://github.com/openai/CLIP/blob/main/LICENSE):

  Copyright (c) 2021 OpenAI

  Permission is hereby granted, free of charge, to any person obtaining a copy of this software
  and associated documentation files (the "Software"), to deal in the Software without
  restriction, including without limitation the rights to use, copy, modify, merge, publish,
  distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
  Software is furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all copies or
  substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
  BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Conversion (tokenizer graph, pooling, projection, normalisation, optional int8 quantization) was
done by iped-semantic/tools/export-models/export_visual.py; it changes the numeric format of the
weights, not their origin or license.
"""


def artifact_sha256(files):
    """As LocalOnnxEmbedder.artifactSha256: sha256 of the lines '<role>:<file sha256>' by role."""
    import hashlib
    lines = "".join("%s:%s\n" % (role, base.sha256_of(path)) for role, path in sorted(files.items()))
    return hashlib.sha256(lines.encode("utf-8")).hexdigest()


def export_visual(out_dir, work_dir):
    os.makedirs(work_dir, exist_ok=True)
    target = os.path.join(out_dir, "models", "semantic", "visual", VISUAL_DIR_NAME)
    os.makedirs(target, exist_ok=True)

    text_path, text_report = export_text_graph(work_dir, target)
    image_path, image_report, probes = export_image_graph(work_dir, target)

    variant = "onnx-%s-ortx" % "-".join(["text%s" % ("int8" if text_report["quantized"] else "fp32"),
                                           "image%s" % ("int8" if image_report["quantized"] else "fp32")])
    sha = artifact_sha256({"text": text_path, "image": image_path})
    meta = {
        "id": "%s@%s+%s@%s+%s" % (TEXT_REPO, TEXT_REVISION, IMAGE_REPO, IMAGE_REVISION, variant),
        "sha256": sha,
        "modalities": ["text", "image"],
        "dimensions": DIMENSIONS,
        "similarity": "DOT_PRODUCT",
        "normalized": True,
        "queryPrefix": "",
        "passagePrefix": "",
        "maxTokens": MAX_TOKENS,
        "files": {"text": "text.onnx", "image": "image.onnx"},
        "textInput": "text",
        "output": "embedding",
        "imageInput": "pixel_values",
        "imageOutput": "image_embedding",
        "image": {"size": IMAGE_SIZE, "resample": "bicubic", "crop": "center", "rescale": 1 / 255.0,
                  "mean": CLIP_MEAN, "std": CLIP_STD},
        "probesVersion": base.PROBES_VERSION,
        "source": {
            "text": {"repository": TEXT_REPO, "revision": TEXT_REVISION, "file": "onnx/model.onnx",
                     "license": "Apache-2.0 (model card)"},
            "image": {"repository": IMAGE_REPO, "revision": IMAGE_REVISION, "license": "MIT (openai/CLIP)"},
        },
        "export": {
            "tool": "iped-semantic/tools/export-models/export_visual.py",
            "quantization": "dynamic, weights QUInt8, per_channel=true, reduce_range=false, kept only at "
                            "cosine >= %s from the fp32 reference" % QUANT_MIN_COSINE,
            "versions": dict(base.package_versions(), torch=__import__("torch").__version__),
            "verification": {"text": text_report, "image": image_report, "imageProbes": probes},
        },
    }
    with open(os.path.join(target, "model.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(target, "LICENSE"), "w", encoding="utf-8", newline="\n") as f:
        f.write(LICENSE_TEXT.format(text_repo=TEXT_REPO, text_rev=TEXT_REVISION, image_repo=IMAGE_REPO,
                                    image_rev=IMAGE_REVISION))

    zip_path = os.path.join(out_dir, "semantic-visual-%s.zip" % VISUAL_DIR_NAME)
    base.make_zip(target, zip_path, root=VISUAL_DIR_NAME)
    print(json.dumps({"target": target, "sha256": sha, "zip": zip_path, "id": meta["id"],
                      "text": text_report, "image": image_report}, indent=2, ensure_ascii=False))
