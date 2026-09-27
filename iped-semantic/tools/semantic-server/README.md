# semantic-server — reference server of `iped-embed/1`

This tool runs **outside** IPED and is not part of the release. It serves one model folder exported
by [`../export-models`](../export-models/README.md) over the `iped-embed/1` protocol
([contract](../../../specs/009-semantic-search/contracts/remote-model-protocol.md)), so that:

1. a lab can host the model on a machine with more capacity (a GPU host, a shared server) without
   writing code, and IPED workstations point `textModelSource`/`visualModelSource = remote` at it;
2. the remote path can be tested without depending on a third-party service (SC-016).

It serves the **same ONNX artifacts** as the IPED package and reports the **same identity** (id and
artifact sha256, computed as `LocalOnnxEmbedder` computes it). A case prepared through this server
is therefore searchable with the identical local model, and the other way round — amendment A4,
verified by `SourceSwapIT`, which runs this script.

## Running it

```powershell
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
$env:IPED_EMBED_CREDENTIAL = "<a long random secret>"
.venv\Scripts\python semantic_server.py --model-dir <IPED>\models\semantic\text\multilingual-e5-small-int8 --port 8765
```

One instance serves one model. For both modalities, run two instances on two ports, one with the
text model folder and one with the visual one.

| Option | Default | |
|---|---|---|
| `--model-dir` | — | A folder with `model.json` and its ONNX files |
| `--host` | `127.0.0.1` | Listen on loopback unless the server is meant to be reached from other machines |
| `--port` | `8765` | `0` picks a free port; the line `serving … at <url>` says which |
| `--credential-env` | `IPED_EMBED_CREDENTIAL` | Environment variable with the credential clients must present |
| `--threads` | `1` | ONNX Runtime threads per inference |
| `--tls-cert`, `--tls-key` | — | Serve HTTPS; required for any client that is not on loopback, since IPED refuses plain HTTP to other hosts unless `remoteAllowPlainHttp = true` |

On the IPED side (`conf/SemanticSearchConfig.txt` of each workstation):

```properties
textModelSource = remote
textRemoteProtocol = iped-embed/1
textRemoteEndpoint = https://gpu-lab.example:8765
textRemoteModel = multilingual-e5-small
textRemoteCredentialEnv = IPED_SEMANTIC_TEXT_CREDENTIAL
```

and the credential in that environment variable (or in a file named by `textRemoteCredentialFile`).
Never in the configuration itself.

## What it guarantees, and why

- **One input per inference run.** With dynamic int8 quantization the composition of a batch changes
  the vectors, and the fingerprint (research D10) compares vectors. IPED's local path does the same.
- **Prefixes by role.** IPED sends `role: query` or `role: passage`; the server applies the model's
  `queryPrefix`/`passagePrefix` from `model.json`.
- **Images as IPED has them.** A thumbnail or a frame cut from the video mosaic, as PNG, never the
  original file. The server prepares it as `model.json` declares (shortest side to 224 with bicubic
  resampling, centre crop, CLIP mean and standard deviation), the recipe IPED's `ImagePreprocessor`
  follows.
- **A credential is required.** Without `Authorization: Bearer <credential>` the answer is 401, and
  the server does not start without one. The comparison is constant-time.
- **Nothing is kept.** No request is written to disk, and the log has method and path only — never a
  body, which carries evidence content, and never a header.

The ONNX Runtime versions in `requirements.txt` are those of the export. The Java side of IPED
runs ONNX Runtime 1.20.0 (see `iped-semantic/pom.xml`); `SourceSwapIT` confirmed on 2026-09-27 that
a case prepared through this server (ONNX Runtime 1.22.1) passes the fingerprint check against the
local artifact.
