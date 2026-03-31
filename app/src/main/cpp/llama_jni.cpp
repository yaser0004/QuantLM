#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstring>
#include <algorithm>

// llama.cpp headers
#include "llama.h"
#include "common.h"
#include "sampling.h"

// Vision/multimodal support (mtmd)
#include "mtmd.h"
#include "mtmd-helper.h"

// For loading images (implementation is in mtmd-helper.cpp)
#include "stb_image.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Maximum image dimension for vision models (to prevent excessively long processing)
// Most mobile vision models work well with images up to 512-768px
static const int MAX_IMAGE_DIMENSION = 512;

/**
 * Model-agnostic stop sequence detection.
 * Checks for common control token patterns that indicate end of assistant response.
 * This is a fallback for when llama_vocab_is_eog() doesn't catch model-specific EOT tokens.
 * 
 * Returns true if a stop pattern is detected and the text should be trimmed.
 * If found, out_trim_pos is set to the position where trimming should occur.
 */
static bool check_stop_pattern(const std::string& text, size_t& out_trim_pos) {
    // Common end-of-turn patterns across different model formats:
    // - <|end|>, <|eot|>, <|eot_id|> - Phi, Llama 3
    // - <|im_end|>, <|endoftext|> - Qwen, ChatML
    // - </s>, <|eos|> - Various models
    // - <|user|>, <|assistant|>, <|human|> - Role markers indicating new turn
    //
    // NOTE:
    // Some models emit role markers at the beginning of the assistant reply
    // (for example: <|assistant|> or <|im_start|>assistant). Treating those
    // as immediate stop tokens can truncate valid outputs to an empty string.
    // We therefore only treat role markers as stop conditions when they appear
    // after at least one generated character.
    
    static const char* hard_stop_patterns[] = {
        // End of turn tokens (should stop and trim here)
        "<|end|>",
        "<|eot|>",
        "<|eot_id|>",
        "<|im_end|>",
        "<|endoftext|>",
        "<|eos|>",
        "</s>",
        nullptr
    };

    static const char* role_marker_patterns[] = {
        // Role markers (stop before these - new turn starting)
        "<|user|>",
        "<|human|>",
        "<|assistant|>",
        "<|system|>",
        "<|im_start|>",
        nullptr
    };

    for (int i = 0; hard_stop_patterns[i] != nullptr; i++) {
        size_t pos = text.find(hard_stop_patterns[i]);
        if (pos != std::string::npos) {
            out_trim_pos = pos;
            return true;
        }
    }

    for (int i = 0; role_marker_patterns[i] != nullptr; i++) {
        size_t pos = text.find(role_marker_patterns[i]);
        // Ignore leading role marker prefixes (position 0), they are often
        // part of the model's assistant-format preamble.
        if (pos != std::string::npos && pos > 0) {
            out_trim_pos = pos;
            return true;
        }
    }
    
    return false;
}

/**
 * Bilinear resize for RGB images
 * Resizes image data to fit within max_dim while preserving aspect ratio
 */
static unsigned char* resize_image_bilinear(
    const unsigned char* src, 
    int src_width, int src_height,
    int* out_width, int* out_height,
    int max_dim) {
    
    // Calculate new dimensions maintaining aspect ratio
    int new_width = src_width;
    int new_height = src_height;
    
    if (src_width > max_dim || src_height > max_dim) {
        float scale = std::min((float)max_dim / src_width, (float)max_dim / src_height);
        new_width = static_cast<int>(src_width * scale);
        new_height = static_cast<int>(src_height * scale);
        
        // Ensure at least 1 pixel
        if (new_width < 1) new_width = 1;
        if (new_height < 1) new_height = 1;
        
        LOGI("Resizing image from %dx%d to %dx%d (scale: %.2f)", 
             src_width, src_height, new_width, new_height, scale);
    } else {
        // No resize needed
        *out_width = src_width;
        *out_height = src_height;
        return nullptr; // Signal no resize needed
    }
    
    unsigned char* dst = new unsigned char[new_width * new_height * 3];
    
    float x_ratio = (float)(src_width - 1) / (new_width > 1 ? new_width - 1 : 1);
    float y_ratio = (float)(src_height - 1) / (new_height > 1 ? new_height - 1 : 1);
    
    for (int y = 0; y < new_height; y++) {
        for (int x = 0; x < new_width; x++) {
            float src_x = x * x_ratio;
            float src_y = y * y_ratio;
            
            int x0 = static_cast<int>(src_x);
            int y0 = static_cast<int>(src_y);
            int x1 = std::min(x0 + 1, src_width - 1);
            int y1 = std::min(y0 + 1, src_height - 1);
            
            float x_diff = src_x - x0;
            float y_diff = src_y - y0;
            
            for (int c = 0; c < 3; c++) {
                float top = src[(y0 * src_width + x0) * 3 + c] * (1 - x_diff) +
                           src[(y0 * src_width + x1) * 3 + c] * x_diff;
                float bottom = src[(y1 * src_width + x0) * 3 + c] * (1 - x_diff) +
                              src[(y1 * src_width + x1) * 3 + c] * x_diff;
                float value = top * (1 - y_diff) + bottom * y_diff;
                
                dst[(y * new_width + x) * 3 + c] = static_cast<unsigned char>(
                    std::min(255.0f, std::max(0.0f, value))
                );
            }
        }
    }
    
    *out_width = new_width;
    *out_height = new_height;
    return dst;
}

/**
 * Safely create a Java string from UTF-8 bytes by decoding into UTF-16.
 * Handles surrogate pairs so 4-byte emoji sequences survive without crashing JNI.
 */
static jstring safeNewStringUTF(JNIEnv* env, const char* bytes) {
    static const jchar EMPTY_STR[] = {0};

    if (bytes == nullptr || bytes[0] == '\0') {
        LOGW("safeNewStringUTF: empty input");
        return env->NewString(EMPTY_STR, 0);
    }

    const unsigned char* ptr = reinterpret_cast<const unsigned char*>(bytes);
    std::vector<jchar> utf16;
    utf16.reserve(strlen(bytes));

    while (*ptr) {
        uint32_t codepoint = 0;
        unsigned char c = *ptr;

        if (c <= 0x7F) {
            codepoint = c;
            ptr++;
        } else if ((c & 0xE0) == 0xC0) {
            if ((ptr[1] & 0xC0) != 0x80) {
                LOGW("safeNewStringUTF: invalid 2-byte sequence start 0x%02X", c);
                ptr++;
                continue;
            }
            codepoint = ((c & 0x1F) << 6) | (ptr[1] & 0x3F);
            ptr += 2;
            if (codepoint < 0x80) {
                LOGW("safeNewStringUTF: overlong 2-byte sequence");
                continue;
            }
        } else if ((c & 0xF0) == 0xE0) {
            if ((ptr[1] & 0xC0) != 0x80 || (ptr[2] & 0xC0) != 0x80) {
                LOGW("safeNewStringUTF: invalid 3-byte sequence start 0x%02X", c);
                ptr++;
                continue;
            }
            codepoint = ((c & 0x0F) << 12) | ((ptr[1] & 0x3F) << 6) | (ptr[2] & 0x3F);
            ptr += 3;
            if (codepoint < 0x800 || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
                LOGW("safeNewStringUTF: invalid 3-byte codepoint 0x%X", codepoint);
                continue;
            }
        } else if ((c & 0xF8) == 0xF0) {
            if ((ptr[1] & 0xC0) != 0x80 || (ptr[2] & 0xC0) != 0x80 || (ptr[3] & 0xC0) != 0x80) {
                LOGW("safeNewStringUTF: invalid 4-byte sequence start 0x%02X", c);
                ptr++;
                continue;
            }
            codepoint = ((c & 0x07) << 18) | ((ptr[1] & 0x3F) << 12) |
                        ((ptr[2] & 0x3F) << 6) | (ptr[3] & 0x3F);
            ptr += 4;
            if (codepoint < 0x10000 || codepoint > 0x10FFFF) {
                LOGW("safeNewStringUTF: invalid 4-byte codepoint 0x%X", codepoint);
                continue;
            }
        } else {
            LOGW("safeNewStringUTF: invalid UTF-8 start byte 0x%02X", c);
            ptr++;
            continue;
        }

        if (codepoint <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            jchar high = static_cast<jchar>(0xD800 + (codepoint >> 10));
            jchar low = static_cast<jchar>(0xDC00 + (codepoint & 0x3FF));
            utf16.push_back(high);
            utf16.push_back(low);
        }
    }

    if (utf16.empty()) {
        return env->NewString(EMPTY_STR, 0);
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

/**
 * Convert UTF-8 chunks to UTF-16 while carrying incomplete multi-byte sequences across calls.
 */
static jstring safeNewStringUTFStreaming(
        JNIEnv* env,
        const std::string& chunk,
        std::string& remainder) {

    std::string combined = remainder;
    combined.append(chunk);

    const unsigned char* ptr = reinterpret_cast<const unsigned char*>(combined.data());
    const size_t total = combined.size();
    std::vector<jchar> utf16;
    utf16.reserve(combined.size());

    size_t index = 0;
    while (index < total) {
        unsigned char c = ptr[index];
        uint32_t codepoint = 0;
        size_t expected = 0;

        if (c <= 0x7F) {
            codepoint = c;
            expected = 1;
        } else if ((c & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((c & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((c & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            LOGW("safeNewStringUTFStreaming: invalid UTF-8 start byte 0x%02X", c);
            index++;
            continue;
        }

        if (index + expected > total) {
            // Incomplete multi-byte sequence, keep in remainder.
            break;
        }

        switch (expected) {
            case 1:
                codepoint = c;
                break;
            case 2: {
                unsigned char c1 = ptr[index + 1];
                if ((c1 & 0xC0) != 0x80) {
                    LOGW("safeNewStringUTFStreaming: invalid continuation byte 0x%02X", c1);
                    index++;
                    continue;
                }
                codepoint = ((c & 0x1F) << 6) | (c1 & 0x3F);
                if (codepoint < 0x80) {
                    LOGW("safeNewStringUTFStreaming: overlong 2-byte sequence");
                    index += 2;
                    continue;
                }
                break;
            }
            case 3: {
                unsigned char c1 = ptr[index + 1];
                unsigned char c2 = ptr[index + 2];
                if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) {
                    LOGW("safeNewStringUTFStreaming: invalid 3-byte continuation");
                    index++;
                    continue;
                }
                codepoint = ((c & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                if (codepoint < 0x800 || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
                    LOGW("safeNewStringUTFStreaming: invalid 3-byte codepoint 0x%X", codepoint);
                    index += 3;
                    continue;
                }
                break;
            }
            case 4: {
                unsigned char c1 = ptr[index + 1];
                unsigned char c2 = ptr[index + 2];
                unsigned char c3 = ptr[index + 3];
                if ((c1 & 0xC0) != 0x80 ||
                    (c2 & 0xC0) != 0x80 ||
                    (c3 & 0xC0) != 0x80) {
                    LOGW("safeNewStringUTFStreaming: invalid 4-byte continuation");
                    index++;
                    continue;
                }
                codepoint = ((c & 0x07) << 18) |
                            ((c1 & 0x3F) << 12) |
                            ((c2 & 0x3F) << 6) |
                            (c3 & 0x3F);
                if (codepoint < 0x10000 || codepoint > 0x10FFFF) {
                    LOGW("safeNewStringUTFStreaming: invalid 4-byte codepoint 0x%X", codepoint);
                    index += 4;
                    continue;
                }
                break;
            }
        }

        if (codepoint <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            jchar high = static_cast<jchar>(0xD800 + (codepoint >> 10));
            jchar low = static_cast<jchar>(0xDC00 + (codepoint & 0x3FF));
            utf16.push_back(high);
            utf16.push_back(low);
        }

        index += expected;
    }

    if (index < total) {
        remainder.assign(reinterpret_cast<const char*>(ptr + index), total - index);
    } else {
        remainder.clear();
    }

    if (utf16.empty()) {
        return env->NewString(nullptr, 0);
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

/**
 * Convert a Java string (UTF-16) to sanitized UTF-8 suitable for llama.cpp consumption.
 * Preserves emoji code points while skipping malformed surrogate sequences.
 */
static std::string sanitizeInputString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        LOGW("sanitizeInputString: null input string");
        return "";
    }

    const jchar* chars = env->GetStringChars(jstr, nullptr);
    if (chars == nullptr) {
        LOGW("sanitizeInputString: failed to acquire chars");
        return "";
    }

    const jsize length = env->GetStringLength(jstr);
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);

    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];

        if (codepoint >= 0xD800 && codepoint <= 0xDBFF) {
            if (i + 1 < length) {
                const jchar low = chars[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    codepoint = (((codepoint - 0xD800) << 10) | (low - 0xDC00)) + 0x10000;
                    ++i;
                } else {
                    LOGW("sanitizeInputString: invalid surrogate pair at index %d", i);
                    continue;
                }
            } else {
                LOGW("sanitizeInputString: truncated surrogate at end of string");
                break;
            }
        } else if (codepoint >= 0xDC00 && codepoint <= 0xDFFF) {
            LOGW("sanitizeInputString: unexpected low surrogate 0x%X at index %d", codepoint, i);
            continue;
        }

        if (codepoint <= 0x7F) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0x10FFFF) {
            result.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            LOGW("sanitizeInputString: invalid codepoint 0x%X", codepoint);
        }
    }

    env->ReleaseStringChars(jstr, chars);
    LOGI("sanitizeInputString: produced %zu bytes from %d code units", result.size(), length);
    return result;
}

// Global state
static std::mutex g_mutex;
// Fix [2.6]: last JNI inference error for LlamaEngine (see InferenceError.kt)
static std::atomic<int> g_last_inference_error_code{0};
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::atomic<bool> g_should_stop{false};
static common_params g_params;

// Vision/multimodal state
static mtmd_context* g_mtmd_ctx = nullptr;
static bool g_is_vision_model = false;

static void configure_sampler_chain(
        llama_sampler* smpl,
        const llama_vocab* vocab,
        int32_t top_k,
        float top_p,
        float temperature,
        float repeat_penalty,
        int32_t repeat_last_n,
        float min_p,
        float tfs_z,
        float typical_p,
        int32_t mirostat,
        float mirostat_tau,
        float mirostat_eta) {
    const int32_t safe_top_k = std::max(1, top_k);
    const float safe_top_p = std::min(1.0f, std::max(0.0f, top_p));
    const float safe_temp = std::max(0.0f, temperature);
    const int32_t safe_repeat_last_n = std::max(0, repeat_last_n);
    const float safe_repeat_penalty = std::max(1.0f, repeat_penalty);
    const float safe_min_p = std::min(1.0f, std::max(0.0f, min_p));
    const float safe_typical_p = std::min(1.0f, std::max(0.0f, typical_p));
    const float safe_tau = std::max(0.0f, mirostat_tau);
    const float safe_eta = std::max(0.001f, mirostat_eta);

    if (tfs_z < 0.999f) {
        LOGW("TFS-Z requested (%.3f) but tail-free sampler is unavailable in this llama build; ignoring", tfs_z);
    }

    if (safe_repeat_last_n > 0 || safe_repeat_penalty > 1.0f) {
        llama_sampler_chain_add(
            smpl,
            llama_sampler_init_penalties(safe_repeat_last_n, safe_repeat_penalty, 0.0f, 0.0f)
        );
    }

    if (mirostat == 1) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(safe_temp));
        llama_sampler_chain_add(
            smpl,
            llama_sampler_init_mirostat(
                llama_vocab_n_tokens(vocab),
                LLAMA_DEFAULT_SEED,
                safe_tau,
                safe_eta,
                100
            )
        );
    } else if (mirostat == 2) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(safe_temp));
        llama_sampler_chain_add(
            smpl,
            llama_sampler_init_mirostat_v2(
                LLAMA_DEFAULT_SEED,
                safe_tau,
                safe_eta
            )
        );
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(safe_top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(safe_top_p, 1));
        if (safe_min_p > 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_min_p(safe_min_p, 1));
        }
        if (safe_typical_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_typical(safe_typical_p, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(safe_temp));
    }

    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

extern "C" {

/**
 * Initialize the LLM library
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeInit(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Initializing Llama native library");
    
    // Initialize llama backend
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);
    
    LOGI("Llama backend initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGetLastInferenceErrorCode(
        JNIEnv* env,
        jobject /* this */) {
    (void)env;
    return static_cast<jint>(g_last_inference_error_code.load());
}

/**
 * Load a model from file path
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeLoadModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath,
        jint nThreads,
        jint nGpuLayers,
        jint contextSize) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);
    LOGI("Threads: %d, GPU Layers: %d, Context: %d", nThreads, nGpuLayers, contextSize);
    
    // Free existing model if any
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    // Set up model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;
    
    // Load model using new API
    g_last_inference_error_code.store(0);
    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        g_last_inference_error_code.store(1); // NATIVE_CODE_MODEL_LOAD_FAILED
        LOGE("Failed to load model from: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
    
    // Set up context parameters
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    
    // Create context using new API
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        g_last_inference_error_code.store(2); // NATIVE_CODE_CONTEXT_INIT_FAILED
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
    
    // Initialize default params
    g_params = common_params();
    g_params.model.path = path;
    g_params.n_ctx = contextSize;
    g_params.cpuparams.n_threads = nThreads;
    
    env->ReleaseStringUTFChars(modelPath, path);
    
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

/**
 * Unload the current model
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeUnloadModel(
        JNIEnv* env,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Unloading model");
    
    // Free llama.cpp resources
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    LOGI("Model unloaded successfully");
}

/**
 * Generate text completion
 */
JNIEXPORT jstring JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGenerate(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
    jint topK,
    jfloat repeatPenalty,
    jint repeatLastN,
    jfloat minP,
    jfloat tfsZ,
    jfloat typicalP,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return safeNewStringUTF(env, "");
    }
    
    const std::string promptStr = sanitizeInputString(env, prompt);
    LOGI("Generating with prompt: %s", promptStr.c_str());
    LOGI("Max tokens: %d, Temperature: %.2f, topP: %.2f, topK: %d, repeatPenalty: %.2f, repeatLastN: %d, minP: %.3f, typicalP: %.3f, mirostat: %d", maxTokens, temperature, topP, topK, repeatPenalty, repeatLastN, minP, typicalP, mirostat);
    
    // Tokenize prompt using common_tokenize
    std::vector<llama_token> tokens_list = common_tokenize(g_ctx, promptStr, true);
    
    const int n_ctx = llama_n_ctx(g_ctx);
    const int n_predict = maxTokens;
    
    // Prepare sampling
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    
    // Create batch
    llama_batch batch = llama_batch_init(n_ctx, 0, 1);
    
    // Get vocab for token operations
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    configure_sampler_chain(
        smpl,
        vocab,
        topK,
        topP,
        temperature,
        repeatPenalty,
        repeatLastN,
        minP,
        tfsZ,
        typicalP,
        mirostat,
        mirostatTau,
        mirostatEta
    );
    
    // Add prompt tokens to batch
    for (size_t i = 0; i < tokens_list.size(); i++) {
        common_batch_add(batch, tokens_list[i], i, {0}, false);
    }
    
    // Prepare for generation
    batch.logits[batch.n_tokens - 1] = true;
    
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to decode");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return safeNewStringUTF(env, "");
    }
    
    int n_cur = batch.n_tokens;
    int n_decode = 0;
    std::string result;
    
    // Generation loop
    while (n_cur <= n_ctx && n_decode < n_predict) {
        // Sample next token
        const llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        
        // Check for EOS (model-aware EOG detection)
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }
        
        // Convert token to text
        std::string piece = common_token_to_piece(g_ctx, new_token_id);
        result.append(piece);
        
        // Model-agnostic stop pattern detection (fallback for edge cases)
        size_t trim_pos;
        if (check_stop_pattern(result, trim_pos)) {
            LOGI("Stop pattern detected at position %zu, ending generation", trim_pos);
            result = result.substr(0, trim_pos);
            break;
        }
        
        // Prepare next batch
        common_batch_clear(batch);
        common_batch_add(batch, new_token_id, n_cur, {0}, true);
        
        n_decode++;
        n_cur++;
        
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode at position %d", n_cur);
            break;
        }
    }
    
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    
    LOGI("Generated %d tokens", n_decode);
    return safeNewStringUTF(env, result.c_str());
}

/**
 * Generate text with streaming callback
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGenerateStream(
        JNIEnv* env,
        jobject thiz,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
    jfloat repeatPenalty,
    jint repeatLastN,
    jfloat minP,
    jfloat tfsZ,
    jfloat typicalP,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
        jobject callback) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return;
    }
    
    g_should_stop.store(false);
    
    const std::string promptStr = sanitizeInputString(env, prompt);
    LOGI("Streaming generation with prompt: %s", promptStr.c_str());
    
    // **CRITICAL FIX: Clear KV cache before each generation**
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, false);  // Clear metadata but keep data buffers
    LOGI("KV cache cleared");
    
    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
    
    // Tokenize prompt using common_tokenize
    std::vector<llama_token> tokens_list = common_tokenize(g_ctx, promptStr, true);
    
    const int n_ctx = llama_n_ctx(g_ctx);
    const int n_predict = maxTokens;
    
    // Get vocab for token operations
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    
    // Prepare sampling
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    configure_sampler_chain(
        smpl,
        vocab,
        topK,
        topP,
        temperature,
        repeatPenalty,
        repeatLastN,
        minP,
        tfsZ,
        typicalP,
        mirostat,
        mirostatTau,
        mirostatEta
    );
    
    // Create batch
    llama_batch batch = llama_batch_init(n_ctx, 0, 1);
    
    // Add prompt tokens to batch
    for (size_t i = 0; i < tokens_list.size(); i++) {
        common_batch_add(batch, tokens_list[i], i, {0}, false);
    }
    
    // Prepare for generation
    batch.logits[batch.n_tokens - 1] = true;
    
    LOGI("Decoding initial batch with %d tokens...", batch.n_tokens);
    int decode_result = llama_decode(g_ctx, batch);
    if (decode_result != 0) {
        LOGE("Failed to decode initial batch, error code: %d", decode_result);
        LOGE("Context size: %d, Batch tokens: %d", n_ctx, batch.n_tokens);
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        
        // Call error callback
        jstring errorMsg = safeNewStringUTF(env, "Failed to decode prompt");
        jclass callbackClass = env->GetObjectClass(callback);
        // Try to call onComplete to prevent hanging
        jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
        env->CallVoidMethod(callback, onCompleteMethod);
        env->DeleteLocalRef(errorMsg);
        return;
    }
    LOGI("Initial batch decoded successfully");
    
    int n_cur = batch.n_tokens;
    int n_decode = 0;
    std::string accumulated_text;
    std::string utf8_remainder;
    
    // Generation loop with streaming
    while (n_cur <= n_ctx && n_decode < n_predict && !g_should_stop.load()) {
        // Sample next token
        const llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        
        // Check for EOS (model-aware EOG detection)
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }
        
        // Convert token to text
        std::string token_str = common_token_to_piece(g_ctx, new_token_id);
        accumulated_text += token_str;
        
        // Model-agnostic stop pattern detection
        size_t trim_pos;
        if (check_stop_pattern(accumulated_text, trim_pos)) {
            LOGI("Stop pattern detected at position %zu, ending generation", trim_pos);
            break;
        }
        
        // Stream the token if not empty - use safe string conversion
        if (!token_str.empty()) {
            LOGI("Token before conversion: length=%zu, first_byte=0x%02X", token_str.length(), (unsigned char)token_str[0]);
            jstring jtoken = safeNewStringUTFStreaming(env, token_str, utf8_remainder);
            if (jtoken != nullptr) {
                jsize jlen = env->GetStringLength(jtoken);
                if (jlen > 0) {
                    env->CallVoidMethod(callback, onTokenMethod, jtoken);
                    if (env->ExceptionCheck()) {
                        LOGE("Exception in onToken callback, clearing and continuing");
                        env->ExceptionClear();
                    }
                }
                env->DeleteLocalRef(jtoken);
            }
        }
        
        // Prepare next batch
        common_batch_clear(batch);
        common_batch_add(batch, new_token_id, n_cur, {0}, true);
        
        n_decode++;
        n_cur++;
        
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode at position %d", n_cur);
            break;
        }
    }
    
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    
    // Flush any remaining partial sequences
    if (!utf8_remainder.empty()) {
        jstring jflush = safeNewStringUTFStreaming(env, "", utf8_remainder);
        if (jflush != nullptr && env->GetStringLength(jflush) > 0) {
            env->CallVoidMethod(callback, onTokenMethod, jflush);
            if (env->ExceptionCheck()) {
                LOGE("Exception in onToken flush callback, clearing");
                env->ExceptionClear();
            }
        }
        if (jflush != nullptr) {
            env->DeleteLocalRef(jflush);
        }
    }

    // Call completion callback
    env->CallVoidMethod(callback, onCompleteMethod);
    
    LOGI("Streaming complete. Generated %d tokens", n_decode);
}

/**
 * Stop ongoing generation
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeStopGeneration(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Stopping generation");
    g_should_stop.store(true);
}

/**
 * Get model information
 */
JNIEXPORT jstring JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGetModelInfo(
        JNIEnv* env,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model) {
        return safeNewStringUTF(env, "No model loaded");
    }
    
    // Get vocab and model metadata
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    const int n_ctx_train = llama_model_n_ctx_train(g_model);
    const int n_embd = llama_model_n_embd(g_model);
    
    char desc[256];
    llama_model_desc(g_model, desc, sizeof(desc));
    
    // Get chat template if available
    const char* chat_template = llama_model_chat_template(g_model, nullptr);
    
    // Format info string
    std::string info = "Model: ";
    info += desc;
    info += "\nVocab: " + std::to_string(n_vocab);
    info += "\nContext (train): " + std::to_string(n_ctx_train);
    info += "\nEmbedding dim: " + std::to_string(n_embd);
    
    if (g_ctx) {
        const int n_ctx = llama_n_ctx(g_ctx);
        info += "\nContext (current): " + std::to_string(n_ctx);
    }
    
    if (chat_template) {
        info += "\nChat template: ";
        info += chat_template;
    }
    
    return safeNewStringUTF(env, info.c_str());
}

/**
 * Get the chat template name/type from the model metadata
 * Returns empty string if no template is embedded
 */
JNIEXPORT jstring JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGetChatTemplate(
        JNIEnv* env,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model) {
        return safeNewStringUTF(env, "");
    }
    
    const char* chat_template = llama_model_chat_template(g_model, nullptr);
    
    if (chat_template) {
        LOGI("Model chat template: %s", chat_template);
        return safeNewStringUTF(env, chat_template);
    }
    
    return safeNewStringUTF(env, "");
}

/**
 * Cleanup resources
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeCleanup(
        JNIEnv* env,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Cleaning up native resources");
    
    // Cleanup vision resources
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    g_is_vision_model = false;
    
    // Cleanup llama.cpp resources
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    llama_backend_free();
    
    LOGI("Native cleanup complete");
}

/**
 * Load a vision model with mmproj
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeLoadVisionModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath,
        jstring mmprojPath,
        jint nThreads,
        jint nGpuLayers,
        jint contextSize) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    const char* model_path = env->GetStringUTFChars(modelPath, nullptr);
    const char* mmproj_path = env->GetStringUTFChars(mmprojPath, nullptr);
    
    LOGI("Loading vision model from: %s", model_path);
    LOGI("Loading mmproj from: %s", mmproj_path);
    LOGI("Threads: %d, GPU Layers: %d, Context: %d", nThreads, nGpuLayers, contextSize);
    
    // Free existing resources
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    // Load the text model first
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;
    
    g_last_inference_error_code.store(0);
    g_model = llama_model_load_from_file(model_path, model_params);
    if (!g_model) {
        g_last_inference_error_code.store(1);
        LOGE("Failed to load model from: %s", model_path);
        env->ReleaseStringUTFChars(modelPath, model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return JNI_FALSE;
    }
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        g_last_inference_error_code.store(2);
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return JNI_FALSE;
    }
    
    // Initialize mtmd context for vision
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = (nGpuLayers > 0);
    mtmd_params.n_threads = nThreads;
    mtmd_params.print_timings = true;
    
    g_mtmd_ctx = mtmd_init_from_file(mmproj_path, g_model, mtmd_params);
    if (!g_mtmd_ctx) {
        g_last_inference_error_code.store(3);
        LOGE("Failed to initialize mtmd context from: %s", mmproj_path);
        llama_free(g_ctx);
        g_ctx = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return JNI_FALSE;
    }
    
    g_is_vision_model = mtmd_support_vision(g_mtmd_ctx);
    
    env->ReleaseStringUTFChars(modelPath, model_path);
    env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
    
    LOGI("Vision model loaded successfully. Vision supported: %s", g_is_vision_model ? "yes" : "no");
    return JNI_TRUE;
}

/**
 * Unload vision model
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeUnloadVisionModel(
        JNIEnv* env,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Unloading vision model");
    
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    g_is_vision_model = false;
    
    LOGI("Vision model unloaded");
}

/**
 * Check if vision is supported
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeIsVisionSupported(
        JNIEnv* env,
        jobject /* this */) {
    return g_is_vision_model ? JNI_TRUE : JNI_FALSE;
}

/**
 * Load image as bitmap for mtmd (with automatic resizing for large images)
 */
static mtmd_bitmap* load_image_from_file(const char* image_path) {
    int width, height, channels;
    unsigned char* data = stbi_load(image_path, &width, &height, &channels, 3); // Force RGB
    
    if (!data) {
        LOGE("Failed to load image from: %s", image_path);
        return nullptr;
    }
    
    LOGI("Loaded image: %dx%d, channels: %d", width, height, channels);
    
    // Resize if image is too large (prevents excessively long processing times)
    int final_width = width;
    int final_height = height;
    unsigned char* resized_data = resize_image_bilinear(
        data, width, height, 
        &final_width, &final_height, 
        MAX_IMAGE_DIMENSION);
    
    mtmd_bitmap* bitmap;
    if (resized_data != nullptr) {
        // Use resized data
        LOGI("Using resized image: %dx%d", final_width, final_height);
        bitmap = mtmd_bitmap_init(final_width, final_height, resized_data);
        delete[] resized_data;
    } else {
        // Original image was small enough
        bitmap = mtmd_bitmap_init(width, height, data);
    }
    
    stbi_image_free(data);
    
    return bitmap;
}

/**
 * Generate text with image (non-streaming)
 */
JNIEXPORT jstring JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGenerateWithImage(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jstring imagePath,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
    jint topK,
    jfloat repeatPenalty,
    jint repeatLastN,
    jfloat minP,
    jfloat tfsZ,
    jfloat typicalP,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx || !g_mtmd_ctx) {
        LOGE("Vision model not loaded");
        return safeNewStringUTF(env, "");
    }
    
    const std::string promptStr = sanitizeInputString(env, prompt);
    const char* image_path = env->GetStringUTFChars(imagePath, nullptr);
    
    LOGI("Generating with image: %s", image_path);
    LOGI("Prompt: %s", promptStr.c_str());
    
    // Load the image
    mtmd_bitmap* bitmap = load_image_from_file(image_path);
    if (!bitmap) {
        env->ReleaseStringUTFChars(imagePath, image_path);
        return safeNewStringUTF(env, "Failed to load image");
    }
    
    // Create input chunks with image marker
    std::string full_prompt = std::string(mtmd_default_marker()) + "\n" + promptStr;
    
    // Tokenize the prompt with image
    mtmd_input_text input_text = {
        .text = full_prompt.c_str(),
        .add_special = true,
        .parse_special = true
    };
    
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap*> bitmaps = {bitmap};
    
    int32_t result = mtmd_tokenize(g_mtmd_ctx, chunks, &input_text, bitmaps.data(), bitmaps.size());
    if (result != 0) {
        LOGE("Failed to tokenize vision input, error: %d", result);
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(imagePath, image_path);
        return safeNewStringUTF(env, "Failed to process image");
    }
    
    // Clear KV cache
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, false);
    
    // Use helper function to process all chunks properly
    int32_t n_batch = 512;
    llama_pos new_n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        g_mtmd_ctx,
        g_ctx,
        chunks,
        0,              // n_past
        0,              // seq_id
        n_batch,
        true,           // logits_last
        &new_n_past
    );
    
    if (eval_result != 0) {
        LOGE("Failed to evaluate vision chunks, error: %d", eval_result);
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(imagePath, image_path);
        return safeNewStringUTF(env, "");
    }
    
    int n_pos = (int)new_n_past;
    
    // Now generate response
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    configure_sampler_chain(
        smpl,
        vocab,
        topK,
        topP,
        temperature,
        repeatPenalty,
        repeatLastN,
        minP,
        tfsZ,
        typicalP,
        mirostat,
        mirostatTau,
        mirostatEta
    );
    
    std::string generated;
    llama_batch batch = llama_batch_init(1, 0, 1);
    
    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(smpl, g_ctx, -1);
        
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        
        std::string piece = common_token_to_piece(g_ctx, token);
        generated += piece;
        
        // Model-agnostic stop pattern detection
        size_t trim_pos;
        if (check_stop_pattern(generated, trim_pos)) {
            generated = generated.substr(0, trim_pos);
            break;
        }
        
        common_batch_clear(batch);
        common_batch_add(batch, token, n_pos++, {0}, true);
        
        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
    }
    
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    mtmd_bitmap_free(bitmap);
    mtmd_input_chunks_free(chunks);
    env->ReleaseStringUTFChars(imagePath, image_path);
    
    LOGI("Vision generation complete, generated %zu chars", generated.size());
    return safeNewStringUTF(env, generated.c_str());
}

/**
 * Generate text with image (streaming)
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeGenerateStreamWithImage(
        JNIEnv* env,
        jobject thiz,
        jstring prompt,
        jstring imagePath,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
    jfloat repeatPenalty,
    jint repeatLastN,
    jfloat minP,
    jfloat tfsZ,
    jfloat typicalP,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
        jobject callback) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx || !g_mtmd_ctx) {
        LOGE("Vision model not loaded");
        return;
    }
    
    g_should_stop.store(false);
    
    const std::string promptStr = sanitizeInputString(env, prompt);
    const char* image_path = env->GetStringUTFChars(imagePath, nullptr);
    
    LOGI("Streaming generation with image: %s", image_path);
    
    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
    
    // Load the image
    mtmd_bitmap* bitmap = load_image_from_file(image_path);
    if (!bitmap) {
        env->ReleaseStringUTFChars(imagePath, image_path);
        env->CallVoidMethod(callback, onCompleteMethod);
        return;
    }
    
    // Create input chunks with image marker
    std::string full_prompt = std::string(mtmd_default_marker()) + "\n" + promptStr;
    
    mtmd_input_text input_text = {
        .text = full_prompt.c_str(),
        .add_special = true,
        .parse_special = true
    };
    
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap*> bitmaps = {bitmap};
    
    int32_t result = mtmd_tokenize(g_mtmd_ctx, chunks, &input_text, bitmaps.data(), bitmaps.size());
    if (result != 0) {
        LOGE("Failed to tokenize vision input");
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(imagePath, image_path);
        env->CallVoidMethod(callback, onCompleteMethod);
        return;
    }
    
    // Clear KV cache
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, false);
    
    // Get batch size from context (default 512 is usually safe)
    int32_t n_batch = 512;
    
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    LOGI("Processing %zu input chunks using mtmd_helper...", n_chunks);
    
    // Use the helper function which properly handles all chunk types
    llama_pos new_n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        g_mtmd_ctx,
        g_ctx,
        chunks,
        0,              // n_past (start from 0)
        0,              // seq_id
        n_batch,
        true,           // logits_last (we want logits for generation)
        &new_n_past
    );
    
    if (eval_result != 0) {
        LOGE("Failed to evaluate chunks, error: %d", eval_result);
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(imagePath, image_path);
        env->CallVoidMethod(callback, onCompleteMethod);
        return;
    }
    
    int n_pos = (int)new_n_past;
    LOGI("All chunks processed successfully, n_pos=%d, starting token generation...", n_pos);
    
    // Generate with streaming
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    configure_sampler_chain(
        smpl,
        vocab,
        topK,
        topP,
        temperature,
        repeatPenalty,
        repeatLastN,
        minP,
        tfsZ,
        typicalP,
        mirostat,
        mirostatTau,
        mirostatEta
    );
    
    std::string accumulated;
    std::string utf8_remainder;
    llama_batch batch = llama_batch_init(1, 0, 1);
    int n_decode = 0;
    
    while (n_decode < maxTokens && !g_should_stop.load()) {
        llama_token token = llama_sampler_sample(smpl, g_ctx, -1);
        
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        
        std::string piece = common_token_to_piece(g_ctx, token);
        accumulated += piece;
        
        // Model-agnostic stop pattern detection
        size_t trim_pos;
        if (check_stop_pattern(accumulated, trim_pos)) {
            LOGI("Stop pattern detected at position %zu, stopping generation", trim_pos);
            break;
        }
        
        // Stream the token
        if (!piece.empty()) {
            jstring jtoken = safeNewStringUTFStreaming(env, piece, utf8_remainder);
            if (jtoken != nullptr && env->GetStringLength(jtoken) > 0) {
                env->CallVoidMethod(callback, onTokenMethod, jtoken);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
            if (jtoken) {
                env->DeleteLocalRef(jtoken);
            }
        }
        
        common_batch_clear(batch);
        common_batch_add(batch, token, n_pos++, {0}, true);
        n_decode++;
        
        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
    }
    
    // Flush remaining
    if (!utf8_remainder.empty()) {
        jstring jflush = safeNewStringUTFStreaming(env, "", utf8_remainder);
        if (jflush && env->GetStringLength(jflush) > 0) {
            env->CallVoidMethod(callback, onTokenMethod, jflush);
        }
        if (jflush) {
            env->DeleteLocalRef(jflush);
        }
    }
    
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    mtmd_bitmap_free(bitmap);
    mtmd_input_chunks_free(chunks);
    env->ReleaseStringUTFChars(imagePath, image_path);
    
    env->CallVoidMethod(callback, onCompleteMethod);
    LOGI("Vision streaming complete, generated %d tokens", n_decode);
}

} // extern "C"
