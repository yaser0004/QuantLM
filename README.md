# QuantLM

Private AI chat on your Android phone.

QuantLM brings fast, local AI experiences to mobile with a clean chat interface, vision and audio support, and a broad model library. It is designed for everyday use, with privacy and control built in.

## Why People Use QuantLM

- Chat with AI directly on your device — no internet required after download
- Ask questions about photos and screenshots
- Use voice input for faster conversations
- Access models with reasoning, vision, and audio capabilities
- Keep your chats and settings on your phone

## Key Features

### Smart AI Chat

- Natural multi-turn conversations
- Streaming replies for a responsive feel
- Conversation history so you can continue where you left off
- Thinking / chain-of-thought display for reasoning-capable models

### Vision Support

- Attach images from camera or gallery
- Ask questions about what is in an image
- Quick actions: Describe, Read Text, Analyze, Identify, and Translate

### Audio Support

- Multimodal models (Gemma 4) accept audio input alongside text
- Speak your prompt with microphone input
- Listen to responses with text-to-speech

### Skills

- Built-in skill manager for extending what the AI can do
- Skills run locally alongside the model

### Easy Model Experience

- Browse and download models in-app
- Track progress with clear download status
- Import local model files (GGUF, .task, .litertlm)
- Switch models anytime from the app

### Personal and Secure

- Optional app lock (PIN, password, pattern, biometric)
- Theme options (system, light, dark)
- No forced account setup for normal use

## Model Library

QuantLM ships with a curated library across four model families:

### SmolLM / SmolVLM — by Hugging Face

| Model | Params | Format | Notes |
|---|---|---|---|
| SmolVLM 1.8B Q8 | 1.8B | GGUF | Vision |
| SmolVLM2 2.2B Q8 | 2.2B | GGUF | Vision |
| SmolLM3 3B Q4 | 3B | GGUF | Reasoning |
| SmolLM3 3B Q8 | 3B | GGUF | Reasoning, higher quality |

### Qwen — by Alibaba

| Model | Params | Format | Notes |
|---|---|---|---|
| Qwen 2.5 1.5B Q8 | 1.5B | LiteRT | Fast text chat |
| Qwen2.5-VL 3B Q8 | 3B | GGUF | Vision |
| Qwen3-VL 2B F16 | 2B | GGUF | Vision, full precision |

### Phi — by Microsoft

| Model | Params | Format | Notes |
|---|---|---|---|
| Phi-4 Mini Q8 | 3.8B | LiteRT | Coding, reasoning |
| Phi-4 Mini Q3 | 3.8B | GGUF | Light, fast |
| Phi-4 Mini Q4 | 3.8B | GGUF | Recommended balance |
| Phi-4 Mini Q6 | 3.8B | GGUF | Near-lossless quality |

### Gemma — by Google

| Model | Params | Format | Notes |
|---|---|---|---|
| Gemma 4 E2B IT | ~2B | LiteRT-LM | Vision + Audio, Reasoning, 32K |
| Gemma 4 E4B IT | ~4B | LiteRT-LM | Vision + Audio, Reasoning, 32K |
| Gemma 3 1B IT | 1B | LiteRT-LM | Fast text chat |

## Quick Start

1. Open QuantLM.
2. Go to Models and download one model.
3. Load the model and start chatting.
4. Optionally attach an image, use voice input, or enable skills.

## Requirements

- Android 10 (API 29) or newer
- RAM varies by model — see each model's details in-app

## Privacy

QuantLM is designed for on-device use. Your conversations, downloaded models, and settings remain on your phone unless you explicitly choose to share content.

## Permissions

QuantLM only requests permissions needed for features you use: camera (image chat), microphone (voice input and audio models), notifications (download updates), and biometrics (app lock).

## Availability

You can install QuantLM from this repository's release APK builds.

## Feedback

Suggestions and bug reports are welcome via GitHub Issues.

## License

Custom Source-Available License. See LICENSE.
