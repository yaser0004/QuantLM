# Llama.cpp Integration Guide

This directory contains the JNI bridge for llama.cpp integration.

## Setup Instructions

### Option 1: Add llama.cpp as a Git Submodule

```bash
cd app/src/main/cpp
git submodule add https://github.com/ggerganov/llama.cpp.git llama-cpp
git submodule update --init --recursive
```

### Option 2: Manual Download

1. Download llama.cpp from: https://github.com/ggerganov/llama.cpp
2. Extract to `app/src/main/cpp/llama-cpp/`
3. Ensure the following files are present:
   - `llama.cpp`
   - `llama.h`
   - `ggml.c`
   - `ggml.h`
   - `common/common.cpp`
   - `common/common.h`

### After Adding llama.cpp

Uncomment the source files in `CMakeLists.txt`:
- Uncomment llama.cpp source file includes
- Add any additional required source files

## Current Status

The JNI bridge (`llama_jni.cpp`) is implemented with placeholder functions.
Once llama.cpp is integrated, uncomment the TODO sections to enable:
- Model loading
- Text generation
- Streaming inference
- Model information retrieval

## Native Methods

All native methods are prefixed with `Java_com_quantlm_yaser_data_inference_LlamaEngine_native*`

- `nativeInit()` - Initialize the library
- `nativeLoadModel()` - Load a GGUF model
- `nativeUnloadModel()` - Unload current model
- `nativeGenerate()` - Synchronous text generation
- `nativeGenerateStream()` - Streaming text generation
- `nativeStopGeneration()` - Cancel ongoing generation
- `nativeGetModelInfo()` - Get model metadata
- `nativeCleanup()` - Cleanup resources
