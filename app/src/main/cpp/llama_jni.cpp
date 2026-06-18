#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstring>
#include <algorithm>
#include <malloc.h>

// llama.cpp headers
#include "llama.h"
#include "common.h"
#include "sampling.h"

// Vision/multimodal support (mtmd)
#include "mtmd.h"
#include "mtmd-helper.h"

// For loading images (implementation is in mtmd-helper.cpp)
#include "stb_image.h"

// ggml backend device enumeration (runtime GPU detection)
#include "ggml-backend.h"

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
// Longest stop pattern below is "<|endoftext|>" (13 bytes); 16 gives margin.
// Generation loops scan only the tail window that the latest piece could have
// completed a pattern in — earlier regions were already scanned on earlier
// tokens, so detection order and results are identical to a full scan while
// avoiding an O(n^2) re-scan of the whole reply on every token.
static const size_t MAX_STOP_PATTERN_LEN = 16;

static bool check_stop_pattern(const std::string& text, size_t& out_trim_pos,
                               size_t scan_from = 0) {
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
        size_t pos = text.find(hard_stop_patterns[i], scan_from);
        if (pos != std::string::npos) {
            out_trim_pos = pos;
            return true;
        }
    }

    for (int i = 0; role_marker_patterns[i] != nullptr; i++) {
        size_t pos = text.find(role_marker_patterns[i], scan_from);
        // Ignore leading role marker prefixes (position 0), they are often
        // part of the model's assistant-format preamble.
        if (pos != std::string::npos && pos > 0) {
            out_trim_pos = pos;
            return true;
        }
    }

    return false;
}

// Tail-window start for check_stop_pattern after appending a piece of
// `piece_len` bytes to a buffer now `total_len` long: any newly-completable
// match must start within the last piece_len + MAX_STOP_PATTERN_LEN bytes.
static size_t stop_scan_from(size_t total_len, size_t piece_len) {
    const size_t window = piece_len + MAX_STOP_PATTERN_LEN;
    return total_len > window ? total_len - window : 0;
}

// Token micro-batching for the streaming JNI callbacks: decoded pieces are
// accumulated natively and flushed across the JNI boundary at most every
// STREAM_FLUSH_INTERVAL_MS (or once STREAM_FLUSH_MAX_BYTES are pending, or at
// end of generation). The Kotlin consumer throttles UI emissions to >=400 ms
// and renders the accumulated text, so the delivered content is byte-identical
// — this only removes per-token NewString/CallVoidMethod overhead.
static const size_t STREAM_FLUSH_MAX_BYTES = 256;
static const int64_t STREAM_FLUSH_INTERVAL_MS = 64;

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

// KV prefix-cache: the exact token sequence currently materialized in the
// context's KV cache (the previous turn's prompt + the tokens it generated).
// The next generation reuses the longest common prefix with this sequence
// instead of re-decoding the whole prompt — turning every follow-up turn from
// a full re-prefill into an incremental decode of only the new tokens.
// Invalidated on model load/unload and on explicit conversation reset; a
// decode failure also clears it so the next turn falls back to a full prefill.
// Guarded by g_mutex (only ever touched while the lock is held).
static std::vector<llama_token> g_cached_tokens;

// Longest common prefix length between two token sequences.
static size_t common_prefix_length(const std::vector<llama_token>& a,
                                    const std::vector<llama_token>& b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) {
        i++;
    }
    return i;
}

// Clamp a tokenized prompt to the context capacity, keeping the most recent
// tokens, with a small margin left for generation. Required because both
// common_batch_add (capacity overrun) and llama_decode (n_tokens > n_batch)
// fail via GGML_ASSERT — a process abort the JVM cannot catch — rather than
// returning an error.
static void clamp_prompt_to_context(std::vector<llama_token>& tokens, int n_ctx) {
    const int max_prompt_tokens = n_ctx > 16 ? n_ctx - 16 : n_ctx;
    if ((int) tokens.size() > max_prompt_tokens) {
        LOGW("Prompt (%zu tokens) exceeds context capacity (%d); keeping the most recent %d tokens",
             tokens.size(), n_ctx, max_prompt_tokens);
        tokens.erase(tokens.begin(), tokens.end() - max_prompt_tokens);
    }
}

// Decode prompt tokens [start, tokens.size()) into the KV cache in chunks of
// at most the context's logical batch size (default 2048). llama_decode
// GGML_ASSERTs (process SIGABRT) on any batch larger than n_batch, so a long
// prefill must be split. The final chunk requests logits for its last token
// so the sampler has fresh state. Returns false on decode failure.
// Caller must hold g_mutex; batch capacity must be >= llama_n_batch(g_ctx).
static bool decode_prompt_chunked(llama_batch& batch,
                                  const std::vector<llama_token>& tokens,
                                  size_t start) {
    const size_t n_batch = std::max(1u, llama_n_batch(g_ctx));
    for (size_t pos = start; pos < tokens.size(); pos += n_batch) {
        const size_t end = std::min(tokens.size(), pos + n_batch);
        common_batch_clear(batch);
        for (size_t i = pos; i < end; i++) {
            common_batch_add(batch, tokens[i], (llama_pos) i, {0}, false);
        }
        if (end == tokens.size()) {
            batch.logits[batch.n_tokens - 1] = true;
        }
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Prompt prefill failed at tokens [%zu, %zu) of %zu", pos, end, tokens.size());
            return false;
        }
    }
    return true;
}

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

// True once llama_backend_init() has been called. nativeUnloadModel may flip this
// back to false to fully release GPU/Vulkan device state; nativeLoadModel and
// nativeInit re-init lazily on demand.
static std::atomic<bool> g_backend_inited{false};

// GGML_BACKEND_DL builds (arm64): directory holding the packaged backend
// variant libraries (the app's nativeLibraryDir), set from Kotlin before
// nativeInit. Variants are dlopened once per process — llama_backend_free()
// only frees quantize tables and does NOT unload them, so they survive
// model unload/reload cycles.
static std::string g_backend_search_path;
static std::atomic<bool> g_backends_loaded{false};

static void ensure_cpu_backend_loaded() {
    if (g_backends_loaded.exchange(true)) {
        return;
    }
    // Monolithic builds (armeabi-v7a) register the CPU backend statically.
    if (ggml_backend_dev_count() > 0) {
        return;
    }
    // GGML_BACKEND_DL build: score the packaged libggml-cpu-android_* variants
    // against this SoC's HWCAP feature bits (dotprod/i8mm/fp16) and dlopen the
    // best one; unsupported variants score 0 so old cores fall back to the
    // baseline variant — never SIGILL.
    ggml_backend_load_all_from_path(
        g_backend_search_path.empty() ? nullptr : g_backend_search_path.c_str());
    const size_t dev_count = ggml_backend_dev_count();
    LOGI("Dynamic ggml backends loaded from '%s' — %zu device(s) registered",
         g_backend_search_path.c_str(), dev_count);
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev) {
            LOGI("  backend device: %s (%s)",
                 ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
        }
    }
    if (dev_count == 0) {
        LOGE("No ggml backend variant could be loaded — model loads will fail");
    }
}

static void ensure_backend_inited() {
    ensure_cpu_backend_loaded();
    if (!g_backend_inited.exchange(true)) {
        llama_backend_init();
        llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);
        LOGI("llama_backend_init complete");
    }
}

/**
 * Where to look for dynamically loadable ggml backend variants. Must be
 * called before nativeInit / the first model load. No-op for builds with a
 * statically linked CPU backend.
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeSetBackendSearchPath(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    if (path == nullptr) {
        return;
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p != nullptr) {
        g_backend_search_path = p;
        env->ReleaseStringUTFChars(path, p);
        LOGI("Backend search path set to %s", g_backend_search_path.c_str());
    }
}

/**
 * Initialize the LLM library
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeInit(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Initializing Llama native library");
    ensure_backend_inited();
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
        jint contextSize,
        jboolean flashAttn,
        jboolean useMlock) {

    std::lock_guard<std::mutex> lock(g_mutex);

    // Lazy backend init — nativeUnloadModel may have torn the backend down to
    // release Vulkan device buffers; bring it back up before we touch llama_*.
    ensure_backend_inited();

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);
    LOGI("Threads: %d, GPU Layers: %d, Context: %d, FlashAttn: %d, Mlock: %d",
         nThreads, nGpuLayers, contextSize, (int)flashAttn, (int)useMlock);

    // Free existing model if any. The mtmd (vision projector) context holds a
    // pointer to the model it was created with — it must be freed before the
    // model, otherwise a later vision call would use-after-free.
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    g_is_vision_model = false;
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    // A new model means a new tokenizer and a fresh KV cache — any cached
    // token sequence from the previous model is invalid and must never be
    // matched against the new model's prompts.
    g_cached_tokens.clear();

    // Set up model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;
    model_params.use_mlock = (useMlock == JNI_TRUE);
    
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
    // false -> AUTO (llama.cpp enables Flash Attention when beneficial/safe);
    // true -> force-enable.
    ctx_params.flash_attn_type = (flashAttn == JNI_TRUE)
            ? LLAMA_FLASH_ATTN_TYPE_ENABLED
            : LLAMA_FLASH_ATTN_TYPE_AUTO;
    
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

    // CPU discipline: ask llama.cpp to spawn worker threads at low priority
    // and drop the calling thread's nice value too. Without this, inference
    // threads compete with System UI at NORMAL priority and starve it on
    // CPU-bound loads — visible as device-wide jank and SystemUI ANRs even
    // when QuantLM is in the background. set_process_priority lives in
    // llama-cpp/common/common.cpp; the cpuparams.priority field is honored
    // by llama.cpp when it creates its internal worker pool.
    g_params.cpuparams.priority = GGML_SCHED_PRIO_LOW;
    set_process_priority(g_params.cpuparams.priority);

    env->ReleaseStringUTFChars(modelPath, path);

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

/**
 * Unload the current model AND release backend (GPU/Vulkan device buffers).
 *
 * The Gfx-dev row in `dumpsys meminfo` was retaining gigabytes after a normal
 * unload because the ggml Vulkan backend's device buffer allocator survives
 * `llama_free` / `llama_model_free`. Calling `llama_backend_free()` here is
 * what actually returns the Vulkan-mapped pages to the OS. The next load
 * re-inits the backend via `ensure_backend_inited()`.
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeUnloadModel(
        JNIEnv* env,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Unloading model + releasing backend");

    // The mtmd (vision projector) context references the model — free it
    // first so unload always drops *all* native state, even when the Kotlin
    // side only calls the text-model unload path.
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    g_is_vision_model = false;
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_cached_tokens.clear();

    if (g_backend_inited.exchange(false)) {
        llama_backend_free();
        LOGI("llama_backend_free complete (Vulkan/CPU backends released)");
    }

    // Ask Android's allocator (Scudo) to purge cached pages back to the OS. Without
    // this, large arenas can survive the unload and continue counting against the
    // process's RSS — visible in `dumpsys meminfo` as inflated Native Heap.
    // M_PURGE is bionic-specific (NDK 23+).
#if defined(M_PURGE)
    mallopt(M_PURGE, 0);
#endif

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
    // Length only — dumping the full prompt costs a multi-KB logcat write per
    // turn and leaks user content into the system log.
    LOGI("Generating with prompt of %zu bytes", promptStr.size());
    LOGI("Max tokens: %d, Temperature: %.2f, topP: %.2f, topK: %d, repeatPenalty: %.2f, repeatLastN: %d, minP: %.3f, typicalP: %.3f, mirostat: %d", maxTokens, temperature, topP, topK, repeatPenalty, repeatLastN, minP, typicalP, mirostat);
    
    // Tokenize prompt using common_tokenize
    std::vector<llama_token> tokens_list = common_tokenize(g_ctx, promptStr, true);

    const int n_ctx = llama_n_ctx(g_ctx);
    const int n_predict = maxTokens;

    if (tokens_list.empty()) {
        LOGE("Prompt tokenized to zero tokens; nothing to decode");
        return safeNewStringUTF(env, "");
    }
    clamp_prompt_to_context(tokens_list, n_ctx);

    // Prepare sampling
    auto sparams = llama_sampler_chain_default_params();
    // Skip sampler perf counters — measurement overhead with no consumer here.
    sparams.no_perf = true;
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
    
    // This path decodes from position 0, so start from a clean KV cache and
    // invalidate the streaming prefix cache (its tokens would no longer match
    // what is resident in the KV cache after this call).
    llama_memory_clear(llama_get_memory(g_ctx), false);
    g_cached_tokens.clear();

    // Prefill the prompt in n_batch-sized chunks (a single oversized batch
    // would abort the process inside llama_decode).
    if (!decode_prompt_chunked(batch, tokens_list, 0)) {
        LOGE("Failed to decode");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return safeNewStringUTF(env, "");
    }

    int n_cur = (int) tokens_list.size();
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
        if (check_stop_pattern(result, trim_pos, stop_scan_from(result.size(), piece.size()))) {
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
    LOGI("Streaming generation with prompt of %zu bytes", promptStr.size());
    
    // KV prefix-cache (see g_cached_tokens): the KV cache is intentionally NOT
    // cleared here. We instead reuse whatever prefix of it still matches the
    // new prompt and decode only the divergent suffix. The cache is reset
    // explicitly on model load/unload and conversation switch.
    llama_memory_t mem = llama_get_memory(g_ctx);

    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");

    if (env->ExceptionCheck() || onTokenMethod == nullptr || onCompleteMethod == nullptr) {
        LOGE("Failed to resolve StreamCallback methods (onToken/onComplete); aborting stream safely");
        env->ExceptionClear();
        env->DeleteLocalRef(callbackClass);
        return;
    }
    
    // Tokenize prompt using common_tokenize
    std::vector<llama_token> tokens_list = common_tokenize(g_ctx, promptStr, true);

    const int n_ctx = llama_n_ctx(g_ctx);
    const int n_predict = maxTokens;

    if (tokens_list.empty()) {
        LOGE("Prompt tokenized to zero tokens; aborting stream safely");
        env->CallVoidMethod(callback, onCompleteMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(callbackClass);
        return;
    }
    clamp_prompt_to_context(tokens_list, n_ctx);

    // Get vocab for token operations
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    
    // Prepare sampling
    auto sparams = llama_sampler_chain_default_params();
    // Skip sampler perf counters — measurement overhead with no consumer here.
    sparams.no_perf = true;
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
    
    // --- KV prefix reuse -------------------------------------------------
    // Find how much of the cached KV sequence still matches this prompt and
    // drop only the part that diverged. The remaining cells [0, reuse) stay
    // valid, so the model only has to decode tokens_list[reuse..end).
    size_t reuse = common_prefix_length(tokens_list, g_cached_tokens);
    // At least one token must be decoded so the sampler has fresh logits;
    // if the whole prompt is already cached (e.g. a regeneration), re-decode
    // just the final token.
    if (!tokens_list.empty() && reuse >= tokens_list.size()) {
        reuse = tokens_list.size() - 1;
    }
    // Tokens beyond the matched prefix no longer describe this conversation —
    // evict them from the KV cache before decoding the new suffix. Recurrent
    // and hybrid architectures (Mamba/RWKV-style memory) cannot remove a
    // partial range — seq_rm returns false and removes nothing — so fall back
    // to a clean full prefill for them instead of decoding over stale state.
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)reuse, -1)) {
        LOGW("KV partial removal unsupported for this model; clearing memory for a full prefill");
        llama_memory_clear(mem, false);
        g_cached_tokens.clear();
        reuse = 0;
    }
    LOGI("KV prefix reuse: %zu/%zu prompt tokens cached, decoding %zu new tokens",
         reuse, tokens_list.size(), tokens_list.size() - reuse);

    // Create batch
    llama_batch batch = llama_batch_init(n_ctx, 0, 1);

    // Decode the un-cached suffix at its absolute positions, split into
    // n_batch-sized chunks (one oversized batch would abort the process).
    LOGI("Decoding %zu prompt tokens...", tokens_list.size() - reuse);
    if (!decode_prompt_chunked(batch, tokens_list, reuse)) {
        LOGE("Failed to decode initial batch");
        LOGE("Context size: %d, Prompt tokens: %zu", n_ctx, tokens_list.size());
        // The KV cache is now in an unknown state — drop it so the next
        // generation starts from a clean full prefill.
        llama_memory_clear(mem, false);
        g_cached_tokens.clear();
        llama_batch_free(batch);
        llama_sampler_free(smpl);

        // Call completion callback to avoid hanging UI state.
        env->CallVoidMethod(callback, onCompleteMethod);
        if (env->ExceptionCheck()) {
            LOGE("Exception in onComplete callback, clearing");
            env->ExceptionClear();
        }
        env->DeleteLocalRef(callbackClass);
        return;
    }
    LOGI("Initial batch decoded successfully");

    // Track the exact token sequence now resident in the KV cache so the
    // next turn can prefix-match against it. Generated tokens are appended
    // to this as they are decoded below.
    g_cached_tokens = tokens_list;

    int n_cur = (int)tokens_list.size();
    int n_decode = 0;
    std::string accumulated_text;
    std::string utf8_remainder;

    // Micro-batched delivery (see STREAM_FLUSH_INTERVAL_MS). last_flush_ms = 0
    // makes the very first piece flush immediately, so TTFT is unaffected.
    std::string pending_tokens;
    int64_t last_flush_ms = 0;
    auto flush_pending = [&]() {
        if (pending_tokens.empty()) {
            return;
        }
        jstring jtoken = safeNewStringUTFStreaming(env, pending_tokens, utf8_remainder);
        pending_tokens.clear();
        if (jtoken != nullptr) {
            if (env->GetStringLength(jtoken) > 0) {
                env->CallVoidMethod(callback, onTokenMethod, jtoken);
                if (env->ExceptionCheck()) {
                    LOGE("Exception in onToken callback, clearing and continuing");
                    env->ExceptionClear();
                }
            }
            env->DeleteLocalRef(jtoken);
        }
    };
    
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
        if (check_stop_pattern(accumulated_text, trim_pos,
                               stop_scan_from(accumulated_text.size(), token_str.size()))) {
            LOGI("Stop pattern detected at position %zu, ending generation", trim_pos);
            break;
        }
        
        // Queue the piece; flushed in batches across the JNI boundary.
        if (!token_str.empty()) {
            pending_tokens += token_str;
            const int64_t now_ms = ggml_time_ms();
            if (pending_tokens.size() >= STREAM_FLUSH_MAX_BYTES ||
                now_ms - last_flush_ms >= STREAM_FLUSH_INTERVAL_MS) {
                flush_pending();
                last_flush_ms = now_ms;
            }
        }

        // Prepare next batch
        common_batch_clear(batch);
        common_batch_add(batch, new_token_id, n_cur, {0}, true);

        n_decode++;
        n_cur++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode at position %d", n_cur);
            // KV state is now uncertain — invalidate the prefix cache so the
            // next generation starts from a clean full prefill.
            llama_memory_clear(mem, false);
            g_cached_tokens.clear();
            break;
        }
        // The token is now resident in the KV cache — record it so the next
        // turn can reuse it as part of the matched prefix.
        g_cached_tokens.push_back(new_token_id);
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    // Deliver whatever is still micro-batched, then any partial UTF-8 tail.
    flush_pending();
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
    if (env->ExceptionCheck()) {
        LOGE("Exception in onComplete callback, clearing");
        env->ExceptionClear();
    }

    env->DeleteLocalRef(callbackClass);
    
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
 * Reset conversation-scoped state without unloading the model.
 *
 * Called when the user opens a different chat or starts a new one: the cached
 * KV sequence describes the previous conversation and must not be prefix-
 * matched against the new one. Clears the KV cache and the prefix-cache
 * bookkeeping so the next generation does a clean full prefill.
 */
JNIEXPORT void JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeResetConversation(
        JNIEnv* env,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Resetting conversation KV state");
    if (g_ctx) {
        llama_memory_clear(llama_get_memory(g_ctx), false);
    }
    g_cached_tokens.clear();
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

JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeIsVulkanCompiled(
        JNIEnv* env,
        jobject /* this */) {
    (void)env;
    const bool gpu_supported = llama_supports_gpu_offload();
    if (gpu_supported) {
        LOGI("GPU offload backend is available");
    } else {
        LOGW("GPU offload backend is not available; CPU fallback will be used");
    }
    return gpu_supported ? JNI_TRUE : JNI_FALSE;
}

/**
 * Runtime check: whether a GPU backend device actually registered.
 * nativeIsVulkanCompiled() reports compile-time support; this reports whether
 * Vulkan found a usable device at runtime. They differ when GPU support is
 * compiled in but no compatible driver/device is present.
 */
JNIEXPORT jboolean JNICALL
Java_com_quantlm_yaser_data_inference_LlamaEngine_nativeIsVulkanInitialized(
        JNIEnv* env,
        jobject /* this */) {
    (void)env;
    const size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev != nullptr &&
            ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            LOGI("Runtime GPU device present: %s", ggml_backend_dev_name(dev));
            return JNI_TRUE;
        }
    }
    LOGW("No runtime GPU device found; execution will use CPU");
    return JNI_FALSE;
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
    g_cached_tokens.clear();

    // Keep the inited flag in sync — otherwise a load after cleanup would
    // skip ensure_backend_inited() and run against a freed backend.
    if (g_backend_inited.exchange(false)) {
        llama_backend_free();
    }

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

    // Same CPU discipline as the text-only load path: deprioritize the calling
    // thread + steer llama.cpp worker pool to LOW. Without this the vision
    // load + every subsequent generate runs at NORMAL nice and starves
    // SystemUI. See the matching block at the end of nativeLoadModel.
    g_params = common_params();
    g_params.cpuparams.n_threads = nThreads;
    g_params.cpuparams.priority  = GGML_SCHED_PRIO_LOW;
    set_process_priority(g_params.cpuparams.priority);

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
    LOGI("Prompt of %zu bytes", promptStr.size());
    
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
    // Vision input rewrites the KV cache from scratch — invalidate the text
    // streaming prefix cache so a later text turn does not match against it.
    g_cached_tokens.clear();

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
        if (check_stop_pattern(generated, trim_pos, stop_scan_from(generated.size(), piece.size()))) {
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

    if (env->ExceptionCheck() || onTokenMethod == nullptr || onCompleteMethod == nullptr) {
        LOGE("Failed to resolve StreamCallback methods for vision stream; aborting safely");
        env->ExceptionClear();
        env->ReleaseStringUTFChars(imagePath, image_path);
        env->DeleteLocalRef(callbackClass);
        return;
    }
    
    // Load the image
    mtmd_bitmap* bitmap = load_image_from_file(image_path);
    if (!bitmap) {
        env->ReleaseStringUTFChars(imagePath, image_path);
        env->CallVoidMethod(callback, onCompleteMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(callbackClass);
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
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(callbackClass);
        return;
    }
    
    // Clear KV cache
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, false);
    // Audio input rewrites the KV cache from scratch — invalidate the text
    // streaming prefix cache so a later text turn does not match against it.
    g_cached_tokens.clear();

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
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(callbackClass);
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

    // Micro-batched delivery — same scheme as nativeGenerateStream.
    std::string pending_tokens;
    int64_t last_flush_ms = 0;
    auto flush_pending = [&]() {
        if (pending_tokens.empty()) {
            return;
        }
        jstring jtoken = safeNewStringUTFStreaming(env, pending_tokens, utf8_remainder);
        pending_tokens.clear();
        if (jtoken != nullptr) {
            if (env->GetStringLength(jtoken) > 0) {
                env->CallVoidMethod(callback, onTokenMethod, jtoken);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
            env->DeleteLocalRef(jtoken);
        }
    };

    while (n_decode < maxTokens && !g_should_stop.load()) {
        llama_token token = llama_sampler_sample(smpl, g_ctx, -1);
        
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        
        std::string piece = common_token_to_piece(g_ctx, token);
        accumulated += piece;

        // Model-agnostic stop pattern detection
        size_t trim_pos;
        if (check_stop_pattern(accumulated, trim_pos, stop_scan_from(accumulated.size(), piece.size()))) {
            LOGI("Stop pattern detected at position %zu, stopping generation", trim_pos);
            break;
        }
        
        // Queue the piece; flushed in batches across the JNI boundary.
        if (!piece.empty()) {
            pending_tokens += piece;
            const int64_t now_ms = ggml_time_ms();
            if (pending_tokens.size() >= STREAM_FLUSH_MAX_BYTES ||
                now_ms - last_flush_ms >= STREAM_FLUSH_INTERVAL_MS) {
                flush_pending();
                last_flush_ms = now_ms;
            }
        }
        
        common_batch_clear(batch);
        common_batch_add(batch, token, n_pos++, {0}, true);
        n_decode++;
        
        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
    }
    
    // Deliver whatever is still micro-batched, then any partial UTF-8 tail.
    flush_pending();
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
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(callbackClass);
    LOGI("Vision streaming complete, generated %d tokens", n_decode);
}

} // extern "C"
