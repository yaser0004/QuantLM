# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable,*Annotation*

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class androidx.hilt.** { *; }

# Keep Hilt ViewModels annotated with @HiltViewModel.
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { *; }

# Keep custom engine qualifier annotations used in DI wiring.
-keep @interface com.quantlm.yaser.di.LlamaEngineQualifier
-keep @interface com.quantlm.yaser.di.LiteRTEngineQualifier
-keep @interface com.quantlm.yaser.di.TFLiteEngineQualifier

# Keep generated Dagger/Hilt classes used at runtime.
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }

# JNI bridge in llama_jni.cpp resolves these callback methods by name using GetMethodID.
# Keep interface + implementation member names to avoid release-only NoSuchMethodError.
-keep class com.quantlm.yaser.data.inference.LlamaEngine$StreamCallback { *; }
-keepclassmembers class * implements com.quantlm.yaser.data.inference.LlamaEngine$StreamCallback {
    public void onToken(java.lang.String);
    public void onComplete();
    public void onError(java.lang.String);
}

# Suppress optional/compile-only references reported by R8 for release minification.
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
