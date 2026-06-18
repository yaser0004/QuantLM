# TensorFlow Lite GPU Delegate - suppress warnings for missing optional classes
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory { *; }

# TensorFlow Lite Core
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.** { *; }

# LiteRT-LM — the native JNI layer (liblitertlm_jni.so) looks up Java classes and
# methods by their original names at runtime via GetMethodID / GetFieldID. R8 renaming
# these produces mid == null in nativeCreateConversation / nativeSendMessage, which
# triggers an ART JNI abort (SIGABRT). Keep all public API and internal callback classes.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# MediaPipe - suppress warnings for missing optional image classes
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-keep class com.google.mediapipe.** { *; }

# Hilt Dependency Injection
-keep class * extends dagger.hilt.internal.Binding
-keep interface dagger.hilt.internal.Binding

# Jsoup — used by the Web Search feature to parse DuckDuckGo results and scraped
# pages. Jsoup ships optional integrations against absent classes; suppress them.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Gson — keep @SerializedName-annotated fields used for persisted web-source JSON.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.quantlm.yaser.domain.model.WebSourceRef { *; }
