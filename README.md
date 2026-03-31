# QuantLM

QuantLM is a production-ready Android application designed to bring the power of Large Language Models (LLMs) directly onto your mobile device. Its standout capability is the ability to run LLMs **offline**, making AI-powered chat and utilities available anywhere, anytime—no internet connection required.

> [!NOTE]
> The app is currently in **beta**. Core features are stable, but you may encounter occasional rough edges as we continue polishing the experience.

---

##  Key Features & Capabilities

- **Offline LLM Support**  
  Run powerful AI models directly on your Android device without an internet connection. This ensures privacy, reliability, and uninterrupted access to AI features.

- **🖼️ Multimodal Vision Support (NEW)**  
  Ask questions about images with vision-capable models! Features include:
  - **Multiple Images**: Attach up to 4 images per message for comparison or context
  - **Camera Capture**: Take photos directly from within the app
  - **Gallery Import**: Select single or multiple images from your gallery
  - **Quick Actions**: One-tap prompts for common tasks:
    - 📝 **Describe** - Get detailed image descriptions
    - 📄 **Read Text** - OCR/extract text from images
    - 🔍 **Analyze** - Deep analysis and insights
    - 🏷️ **Identify** - Identify objects, places, items
    - 🌐 **Translate** - Translate text found in images
  - **Qwen by Alibaba (Vision)**: Qwen2.5-VL 3B Q8 (3.29GB + 845MB mmproj), Qwen3-VL 2B F16 (3.45GB + 819MB mmproj)

- **Available LLM Models**  
  The following models are currently supported for offline use:
  - **Phi by Microsoft**
    - Phi-3 Mini 4K Q2: 1.32GB (ultra light, fastest)
    - Phi-3 Mini 4K Q3: 1.82GB (light, balanced speed/quality)
    - Phi-3 Mini 4K Q4: 2.39GB (recommended, best balance)
    - Phi-3 Mini 4K Q5: 2.62GB (high quality, minimal loss)
  - **Llama by Meta**
    - Llama 3.2 1B Q8: 1.32GB (1 billion parameters, Q8 quantization)
    - Llama 3.2 3B Q6_K: 2.64GB (3 billion parameters, Q6_K quantization)
  - **Gemma by Google**
    - Gemma 2 2B Q4: 1.61GB (2 billion parameters, Q4 quantization)
    - Gemma 2 2B Q6_K: 2.39GB (2 billion parameters, Q6_K quantization)
    - Gemma 2 9B Q4: 5.44GB (9 billion parameters, Q4 quantization)
  - **SmolVLM/SmolLM by Hugging Face**
    - SmolVLM 256M F16: 328MB + 190MB mmproj (ultra-compact vision)
    - SmolVLM 500M F16: 820MB + 199MB mmproj (compact vision)
    - SmolVLM 1.8B Q8: 1.93GB + 593MB mmproj (higher-quality vision)
    - SmolVLM2 256M Video F16: 328MB + 190MB mmproj (video-tuned)
    - SmolVLM2 500M Video F16: 820MB + 199MB mmproj (video-tuned)
    - SmolVLM2 2.2B Q8: 1.93GB + 593MB mmproj (vision + video-tuned)
    - SmolLM3 3B Q4_K_M: 1.92GB (text-only, balanced)
    - SmolLM3 3B Q8_0: 3.28GB (text-only, high quality)
  - **Qwen by Alibaba**
    - Qwen2.5-VL 3B Q8_0: 3.29GB + 845MB mmproj (multimodal vision-language model)
    - Qwen3-VL 2B F16: 3.45GB + 819MB mmproj (multimodal vision-language model)

- **AI Chat Interface**  
  Converse naturally with LLM-powered AI for information, assistance, and creative tasks.

- **Multi-Turn Dialogues**  
  Maintains conversation context for more coherent and intelligent responses.

- **Customizable AI Settings**  
  Adjust parameters like temperature, response length (max tokens), top-P, top-K, and system prompt to match your needs and fine-tune AI behavior.

- **Modern, Intuitive UI**  
  Built for a smooth, friendly user experience.

- **Privacy-First Design**  
  No login required. Your conversations are stored only on your device unless you choose to share them.

- **Fast Local Responses**  
  Optimized for minimal latency by running LLMs natively.

- **Extensible Architecture**  
  Designed to support additional AI models and features in future releases.

---

##  Installation

The APK package for QuantLM is provided directly in this repository—no build steps required.

1. Download the latest APK from the [Releases](./releases) section or the provided file.
2. Transfer the APK to your Android device and open it to install.
3. (If prompted) Enable installation from unknown sources in your device settings.



##  Feedback

- Report bugs and suggestions via [GitHub Issues](https://github.com/yaser0004/QuantLM/issues).

---

##  License

Distributed under the MIT License. See [LICENSE](./LICENSE) for details.

---

##  Contact

Open a GitHub issue or reach out via GitHub for questions.

---

**Enjoy using QuantLM offline, and help shape its future by sharing your feedback!**
