ONNX Runtime native libraries for semantic search
=================================================

This folder receives the native libraries of the ONNX Runtime, which IPED's semantic search
(module iped-semantic, feature 009) uses to run its models locally, without network access.
Nothing needs to be installed here by hand: the folder starts empty and is filled the first time
semantic search runs, in processing or in the analysis application.

Where the libraries come from
-----------------------------
They are unpacked from two jars shipped in lib/, with the versions fixed after the compatibility
check with the embedded Java 11 runtime (research D2, spike T002):

  com.microsoft.onnxruntime:onnxruntime:1.20.0
      onnxruntime, onnxruntime4j_jni (and onnxruntime_providers_shared when the build has it)
  com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0
      onnxruntime_extensions4j_jni (the tokenizers that run inside the model graph)

Platforms: Windows x64 and Linux x64 (checked). The jars also carry macOS x64 libraries, which
IPED does not use.

How the extraction works
------------------------
- Each platform gets a subfolder: tools/onnxruntime/<os>-<arch>/, e.g. win-x64.
- A library already present with the same SHA-256 as the one inside the jar is left alone, so two
  IPED processes (processing and the analysis application) can use the folder at the same time,
  and a library in use on Windows is never overwritten.
- ONNX Runtime is pointed here through the system property onnxruntime.native.path; the
  extensions library is registered as a custom operator library from this folder. Neither library
  is written to the system temporary folder.
- Deleting this folder is harmless: it is filled again on the next use.

License
-------
ONNX Runtime and ONNX Runtime Extensions: MIT License, Copyright (c) Microsoft Corporation.
See licenses/ONNXRuntime.txt and licenses/ONNXRuntimeExtensions.txt in the IPED folder, and
ThirdPartyNotices.txt inside the onnxruntime jar for the components bundled in the native library.
