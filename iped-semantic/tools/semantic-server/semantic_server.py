#!/usr/bin/env python3
"""Reference server of the iped-embed/1 protocol (specs/009-semantic-search/contracts/remote-model-protocol.md).

It serves ONE model folder exported by iped-semantic/tools/export-models - the same ONNX artifacts
the IPED package runs locally - and reports the same identity (id and artifact sha256), so a case
prepared through this server is searchable with the identical local model and the other way round
(amendment A4). Run one instance per model: text on one port, visual on another.

    IPED_EMBED_CREDENTIAL=<secret> python semantic_server.py --model-dir <folder> [--port 8765]

Rules it keeps, because IPED relies on them:
- one input per inference run: with dynamic int8 quantization the composition of a batch changes
  the vectors, and the fingerprint compares vectors;
- the model's query and passage prefixes are applied here, by the role IPED sends;
- images arrive as the picture IPED has (a thumbnail or a frame of the video mosaic) and are
  prepared as model.json declares, the same recipe IPED's ImagePreprocessor follows;
- a credential is required: a request without "Authorization: Bearer <credential>" is refused with
  401, and the server does not start without one;
- nothing is written to disk and no request content is logged.

Not part of the IPED release. Serve it over HTTPS (--tls-cert/--tls-key) or on loopback: IPED
refuses plain HTTP to any other host unless remoteAllowPlainHttp = true.
"""

import argparse
import base64
import hashlib
import hmac
import io
import json
import os
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path
from PIL import Image

PROTOCOL_PREFIX = "/iped-embed/1"
MAX_BODY_BYTES = 64 * 1024 * 1024


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class Model:
    """A model folder: model.json plus one ONNX file per role, as LocalOnnxEmbedder reads it."""

    def __init__(self, folder, threads):
        with open(os.path.join(folder, "model.json"), encoding="utf-8") as f:
            self.manifest = json.load(f)
        files = self.manifest.get("files") or {"text": "model.onnx"}
        self.paths = {role: os.path.join(folder, name) for role, name in files.items()}
        # identity: the same computation as LocalOnnxEmbedder.artifactSha256
        if len(self.paths) == 1:
            self.sha256 = sha256_of(next(iter(self.paths.values())))
        else:
            lines = "".join("%s:%s\n" % (role, sha256_of(p)) for role, p in sorted(self.paths.items()))
            self.sha256 = hashlib.sha256(lines.encode("utf-8")).hexdigest()
        self.id = self.manifest["id"]
        self.dimensions = int(self.manifest["dimensions"])
        self.query_prefix = self.manifest.get("queryPrefix", "")
        self.passage_prefix = self.manifest.get("passagePrefix", "")
        self.text_input = self.manifest.get("textInput", "text")
        self.text_output = self.manifest.get("output", "embedding")
        self.image_input = self.manifest.get("imageInput", "pixel_values")
        self.image_output = self.manifest.get("imageOutput", "image_embedding")
        image = self.manifest.get("image", {})
        self.size = int(image.get("size", 224))
        self.mean = np.array(image.get("mean", [0.48145466, 0.4578275, 0.40821073]), dtype=np.float32)
        self.std = np.array(image.get("std", [0.26862954, 0.26130258, 0.27577711]), dtype=np.float32)
        so = ort.SessionOptions()
        so.register_custom_ops_library(get_library_path())
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.sessions = {role: ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
                         for role, p in self.paths.items()}

    def modalities(self):
        return sorted(self.sessions.keys())

    def embed_text(self, role, text):
        if "text" not in self.sessions:
            raise ValueError("this model has no text encoder")
        prefix = self.query_prefix if role == "query" else self.passage_prefix
        out = self.sessions["text"].run([self.text_output], {self.text_input: np.array([prefix + text], dtype=object)})
        return out[0][0]

    def embed_image(self, data):
        if "image" not in self.sessions:
            raise ValueError("this model has no image encoder")
        pixels = self.prepare(Image.open(io.BytesIO(data)))
        out = self.sessions["image"].run([self.image_output], {self.image_input: pixels})
        return out[0][0]

    def prepare(self, img):
        """Shortest side to `size` (bicubic), centre crop, [0, 1], mean/std, NCHW float32."""
        img = img.convert("RGB")
        w, h = img.size
        if w <= h:
            nw, nh = self.size, int(self.size * h / w)
        else:
            nw, nh = int(self.size * w / h), self.size
        img = img.resize((nw, nh), resample=Image.BICUBIC)
        top, left = (nh - self.size) // 2, (nw - self.size) // 2
        img = img.crop((left, top, left + self.size, top + self.size))
        arr = np.asarray(img, dtype=np.float32) / 255.0
        arr = (arr - self.mean) / self.std
        return arr.transpose(2, 0, 1)[None, ...].astype(np.float32)


def make_handler(model, credential):
    expected = ("Bearer " + credential).encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "iped-embed-reference/1"

        def log_message(self, fmt, *args):
            # method, path and status only: never a body, never a header
            sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

        def reply(self, status, obj=None):
            body = b"" if obj is None else json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorised(self):
            given = (self.headers.get("Authorization") or "").encode("utf-8")
            if not hmac.compare_digest(given, expected):
                self.reply(401, {"error": "a valid credential is required"})
                return False
            return True

        def identity(self):
            return {"id": model.id, "sha256": model.sha256}

        def do_GET(self):
            if not self.authorised():
                return
            if self.path.split("?")[0] != PROTOCOL_PREFIX + "/model":
                self.reply(404, {"error": "not found"})
                return
            self.reply(200, dict(self.identity(), dimensions=model.dimensions, modalities=model.modalities()))

        def do_POST(self):
            if not self.authorised():
                return
            if self.path.split("?")[0] != PROTOCOL_PREFIX + "/embed":
                self.reply(404, {"error": "not found"})
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY_BYTES:
                self.reply(400, {"error": "missing or oversized body"})
                return
            try:
                request = json.loads(self.rfile.read(length).decode("utf-8"))
                vectors = []
                for item in request.get("inputs", []):
                    if item.get("type") == "text":
                        vectors.append(model.embed_text(item.get("role", "passage"), item.get("text", "")))
                    elif item.get("type") == "image":
                        vectors.append(model.embed_image(base64.b64decode(item["data"])))
                    else:
                        raise ValueError("unknown input type")
            except (ValueError, KeyError, TypeError, OSError) as e:
                self.reply(400, {"error": type(e).__name__})
                return
            self.reply(200, {"model": self.identity(),
                             "embeddings": [[float(x) for x in v] for v in vectors]})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", required=True, help="a model folder exported by export-models")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--credential-env", default="IPED_EMBED_CREDENTIAL",
                        help="environment variable holding the credential clients must present")
    parser.add_argument("--threads", type=int, default=1, help="ONNX Runtime threads per inference")
    parser.add_argument("--tls-cert")
    parser.add_argument("--tls-key")
    args = parser.parse_args()

    credential = os.environ.get(args.credential_env, "").strip()
    if not credential:
        sys.exit("no credential: set %s; this server does not run without one" % args.credential_env)
    model = Model(args.model_dir, args.threads)
    httpd = ThreadingHTTPServer((args.host, args.port), make_handler(model, credential))
    scheme = "http"
    if args.tls_cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.tls_cert, args.tls_key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        scheme = "https"
    # the port actually bound (0 asks the system for a free one); a supervisor reads this line
    print("serving %s (sha256 %s) at %s://%s:%d%s" % (model.id, model.sha256[:12], scheme, args.host,
                                                        httpd.server_address[1], PROTOCOL_PREFIX), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
