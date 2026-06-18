cmake_minimum_required(VERSION 3.22.1)

project("quantlm")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Toggle Vulkan backend support from Gradle/CMake arguments. Note: in both
# branches below this is effectively inert for ggml (the ggml Vulkan backend
# sources are not compiled); kept so the Gradle -DUSE_VULKAN argument and the
# armeabi-v7a branch behave exactly as before.
option(USE_VULKAN "Enable Vulkan backend support" ON)
if(USE_VULKAN)
    find_library(vulkan-lib vulkan)
    if(NOT vulkan-lib)
        message(WARNING "USE_VULKAN is ON but Vulkan library was not found; using CPU backend only.")
    endif()
endif()

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama-cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # =========================================================================
    # arm64-v8a: build llama.cpp through its own CMake with runtime-dispatched
    # CPU backend variants (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS).
    #
    # Why: the old monolithic build compiled ggml at baseline armv8-a, so the
    # dotprod/i8mm quantized-matmul kernels (2-4x faster decode, supported by
    # virtually every 2018+ SoC including low-end Cortex-A55 designs) were
    # never even compiled. With ALL_VARIANTS, seven libggml-cpu-android_*.so
    # variants are packaged; at startup ggml scores each against the SoC's
    # HWCAP feature bits (ggml-cpu/arch/arm/cpu-feats.cpp) and dlopens the
    # best one. Chips lacking a feature score that variant 0 and fall back —
    # ultimately to the baseline android_armv8.0 variant — so there is no
    # SIGILL risk on old cores (Cortex-A53 etc.).
    #
    # The JNI side passes the app's nativeLibraryDir to
    # ggml_backend_load_all_from_path() before llama_backend_init(); this
    # requires android:extractNativeLibs (useLegacyPackaging=true in Gradle)
    # so the variant .so files exist as real files for the directory scan.
    # =========================================================================

    # AGP passes CMAKE_BUILD_TYPE=Debug for debug app variants, but inference
    # must never run at -O0 — the old monolithic build always applied -O3.
    # Force Release for the native tree to keep that behavior.
    set(CMAKE_BUILD_TYPE Release)

    set(BUILD_SHARED_LIBS ON)
    set(GGML_BACKEND_DL ON)
    set(GGML_CPU_ALL_VARIANTS ON)
    set(GGML_NATIVE OFF)
    set(GGML_OPENMP OFF)
    set(GGML_VULKAN OFF)
    set(LLAMA_CURL OFF)
    set(LLAMA_BUILD_COMMON ON)
    # Tools are needed for the mtmd (multimodal/vision) library target.
    set(LLAMA_BUILD_TOOLS ON)
    set(LLAMA_BUILD_TESTS OFF)
    set(LLAMA_BUILD_EXAMPLES OFF)
    set(LLAMA_BUILD_SERVER OFF)

    add_subdirectory(${LLAMA_CPP_DIR} llama-cpp-build)

    # build-info symbols come from the `common` target's generated
    # build-info.cpp in this branch (llama_build_info.cpp is the hand-rolled
    # equivalent used only by the monolithic armeabi-v7a branch below).
    add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp
    )

    target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LLAMA_CPP_DIR}
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/common
        ${LLAMA_CPP_DIR}/tools/mtmd
        ${LLAMA_CPP_DIR}/vendor/stb
        ${LLAMA_CPP_DIR}/vendor
    )

    target_link_libraries(${CMAKE_PROJECT_NAME}
        common
        llama
        mtmd
        ${log-lib}
        ${android-lib}
    )

    target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -O3
        -ffast-math
        -fno-finite-math-only
        -funroll-loops
    )
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE NDEBUG)

    # The CPU variant libraries are dlopened at runtime, not linked, so they
    # are not pulled in by quantlm's link graph. Gradle builds only the
    # `quantlm` target (tools like llama-cli do not cross-compile cleanly in
    # this snapshot and are not wanted) — pin the variants as explicit
    # dependencies so they are built and packaged alongside it. This list
    # mirrors ggml/src/CMakeLists.txt's Android branch; if the submodule is
    # updated and the list changes, CMake fails loudly here.
    add_dependencies(${CMAKE_PROJECT_NAME}
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2
    )

else()
    # =========================================================================
    # armeabi-v7a (and any other ABI): the original monolithic build —
    # everything compiled into a single libquantlm.so. The ARM variant list is
    # aarch64-only upstream, so this branch is byte-for-byte the prior build.
    # =========================================================================

    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto")

    # No-op stand-ins for the arm64 CPU-variant targets that Gradle's
    # ABI-global externalNativeBuild.targets list names — without these,
    # ninja fails on "unknown target" for this ABI.
    foreach(variant_target
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2)
        add_custom_target(${variant_target})
    endforeach()

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
endif()
