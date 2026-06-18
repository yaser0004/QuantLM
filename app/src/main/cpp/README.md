# llama.cpp Integration Guide

This directory contains the JNI bridge for the llama.cpp (GGUF) inference engine.

- `llama_jni.cpp` — the JNI bridge consumed by `LlamaEngine.kt`.
- `llama-cpp/` — the vendored upstream llama.cpp source tree.
- `CMakeLists.txt` — builds the native `quantlm` library against upstream's
  own CMake (`add_subdirectory(llama-cpp)`), linking the `llama`, `common`,
  `mtmd` and `ggml` targets.

## Build requirements

### Vulkan SDK (required)

The native build enables the **Vulkan GPU backend** (`-DGGML_VULKAN=ON`).
Upstream's `ggml-vulkan` CMake:

- compiles `ggml/src/ggml-vulkan/ggml-vulkan.cpp`,
- builds the host-side `vulkan-shaders-gen` tool, and
- runs `glslc` to compile every `ggml/src/ggml-vulkan/vulkan-shaders/*.comp`
  shader into SPIR-V.

Because of this, **every developer and CI build machine must have the Vulkan
SDK installed**, including `glslc`. `ggml-vulkan/CMakeLists.txt` calls
`find_package(Vulkan COMPONENTS glslc REQUIRED)` and the CMake configure step
will fail without it.

Install the Vulkan SDK from <https://vulkan.lunarg.com/> (or your distro's
`vulkan-tools` / `glslang-tools` / `shaderc` packages) and make sure
`glslc` is on `PATH` or `VULKAN_SDK` is set before building.

When cross-compiling for Android, `vulkan-shaders-gen` is built for the build
host using the host compiler (auto-detected by `ggml-vulkan/CMakeLists.txt`),
while `ggml-vulkan` itself is built for the Android target.

### Other tooling

- Android NDK (configured via Android Studio / `local.properties`).
- CMake 3.22.1+ (declared in `app/build.gradle.kts`).

## GPU acceleration

`GGML_VULKAN=ON` registers a Vulkan device with ggml. At runtime
`llama_supports_gpu_offload()` returns true and the `n_gpu_layers` value
(plumbed from `HardwareSettings.gpuLayers`) offloads layers to the GPU.
llama.cpp automatically falls back to the CPU backend when no usable Vulkan
device/driver is found, so enabling GPU layers is safe on all devices.

## Native methods

Native methods are prefixed with
`Java_com_quantlm_yaser_data_inference_LlamaEngine_native*`:

- `nativeInit()` — initialize the library
- `nativeLoadModel()` — load a GGUF model
- `nativeUnloadModel()` — unload the current model
- `nativeGenerate()` — synchronous text generation
- `nativeGenerateStream()` — streaming text generation
- `nativeStopGeneration()` — cancel ongoing generation
- `nativeGetModelInfo()` — get model metadata
- `nativeCleanup()` — clean up resources
