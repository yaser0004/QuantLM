cmake_minimum_required(VERSION 3.22.1)

project("quantlm")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto")

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Toggle Vulkan backend support from Gradle/CMake arguments
option(USE_VULKAN "Enable Vulkan backend support" ON)
if(USE_VULKAN)
    find_library(vulkan-lib vulkan)
    if(NOT vulkan-lib)
        message(WARNING "USE_VULKAN is ON but Vulkan library was not found; using CPU backend only.")
    endif()
endif()

# Add llama.cpp source files
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama-cpp)

# Collect all model graph builder sources (required for llama.cpp models)
file(GLOB LLAMA_MODEL_SOURCES ${LLAMA_CPP_DIR}/src/models/*.cpp)

# Collect mtmd (multimodal) sources for vision support
file(GLOB MTMD_MODEL_SOURCES ${LLAMA_CPP_DIR}/tools/mtmd/models/*.cpp)

# Create the native library with JNI wrapper
add_library(${CMAKE_PROJECT_NAME} SHARED
    llama_jni.cpp
    llama_build_info.cpp
    # llama.cpp core files
    ${LLAMA_CPP_DIR}/src/llama.cpp
    ${LLAMA_CPP_DIR}/src/llama-adapter.cpp
    ${LLAMA_CPP_DIR}/src/llama-arch.cpp
    ${LLAMA_CPP_DIR}/src/llama-batch.cpp
    ${LLAMA_CPP_DIR}/src/llama-chat.cpp
    ${LLAMA_CPP_DIR}/src/llama-context.cpp
    ${LLAMA_CPP_DIR}/src/llama-cparams.cpp
    ${LLAMA_CPP_DIR}/src/llama-graph.cpp
    ${LLAMA_CPP_DIR}/src/llama-hparams.cpp
    ${LLAMA_CPP_DIR}/src/llama-impl.cpp
    ${LLAMA_CPP_DIR}/src/llama-io.cpp
    ${LLAMA_CPP_DIR}/src/llama-kv-cache.cpp
    ${LLAMA_CPP_DIR}/src/llama-kv-cache-iswa.cpp
    ${LLAMA_CPP_DIR}/src/llama-memory.cpp
    ${LLAMA_CPP_DIR}/src/llama-memory-hybrid.cpp
    ${LLAMA_CPP_DIR}/src/llama-memory-recurrent.cpp
    ${LLAMA_CPP_DIR}/src/llama-mmap.cpp
    ${LLAMA_CPP_DIR}/src/llama-model-loader.cpp
    ${LLAMA_CPP_DIR}/src/llama-model-saver.cpp
    ${LLAMA_CPP_DIR}/src/llama-model.cpp
    ${LLAMA_CPP_DIR}/src/llama-quant.cpp
    ${LLAMA_CPP_DIR}/src/llama-vocab.cpp
    ${LLAMA_CPP_DIR}/src/llama-grammar.cpp
    ${LLAMA_CPP_DIR}/src/llama-sampling.cpp
    ${LLAMA_CPP_DIR}/src/unicode-data.cpp
    ${LLAMA_CPP_DIR}/src/unicode.cpp
    # Model-specific graph builders (llama, phi, gemma, qwen, etc.)
    ${LLAMA_MODEL_SOURCES}
    # mtmd (multimodal/vision) files
    ${LLAMA_CPP_DIR}/tools/mtmd/clip.cpp
    ${LLAMA_CPP_DIR}/tools/mtmd/mtmd.cpp
    ${LLAMA_CPP_DIR}/tools/mtmd/mtmd-audio.cpp
    ${LLAMA_CPP_DIR}/tools/mtmd/mtmd-helper.cpp
    ${MTMD_MODEL_SOURCES}
    # ggml core files
    ${LLAMA_CPP_DIR}/ggml/src/ggml.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-opt.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-alloc.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml-backend.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-backend-reg.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-quants.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml-threading.cpp
    ${LLAMA_CPP_DIR}/ggml/src/gguf.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/quants.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/hbm.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/repack.cpp
    # ARM architecture specific files
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp
    # common utilities
    ${LLAMA_CPP_DIR}/common/common.cpp
    ${LLAMA_CPP_DIR}/common/sampling.cpp
    ${LLAMA_CPP_DIR}/common/log.cpp
)

# Include directories
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/ggml/src
    ${LLAMA_CPP_DIR}/ggml/src/ggml-cpu
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/src
    ${LLAMA_CPP_DIR}/src/models
    # mtmd (multimodal/vision) includes
    ${LLAMA_CPP_DIR}/tools/mtmd
    # stb includes for image loading
    ${LLAMA_CPP_DIR}/vendor/stb
    # vendor includes (miniaudio)
    ${LLAMA_CPP_DIR}/vendor
)

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    ${log-lib}
    ${android-lib}
    c++_shared
)

if(USE_VULKAN AND vulkan-lib)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${vulkan-lib})
endif()

# Compiler flags for optimization
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -ffast-math
    -fno-finite-math-only
    -funroll-loops
    -flto
)

# Add preprocessor definitions
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    NDEBUG
    GGML_VERSION="0.9.4"
    GGML_COMMIT="unknown"
)

if(USE_VULKAN AND vulkan-lib)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_VULKAN=1
        GGML_USE_CPU=1
    )
else()
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_USE_CPU=1
    )
endif()
