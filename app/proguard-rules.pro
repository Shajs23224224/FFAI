# ProGuard configuration for FFAI

# Keep public API
-keep public class com.ffai.** { public *; }

# Keep entry points
-keep public class com.ffai.MainActivity
-keep public class com.ffai.FFAIApplication
-keep public class com.ffai.service.FFAIAccessibilityService

# Keep model classes for serialization
-keep class com.ffai.model.** { *; }
-keepclassmembers class com.ffai.model.** { *; }

# Keep memory classes
-keep class com.ffai.memory.** { *; }
-keepclassmembers class com.ffai.memory.** { *; }

# Keep learning classes
-keep class com.ffai.learning.** { *; }
-keepclassmembers class com.ffai.learning.** { *; }

# Keep accessibility service methods
-keepclassmembers class com.ffai.service.FFAIAccessibilityService {
    public void executeTap(float, float, long);
    public void executeSwipe(float, float, float, float, long);
    public void executeContinuousGesture(java.util.List, long);
    public void executeMultiTouch(java.util.List, long);
}

# Keep data classes for Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-keepattributes Signature, Exceptions, *Annotation*
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations

# Kotlin
-keep class kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoaderImpl
-keep class kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.BuiltInsLoaderImpl
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation

# Kotlinx Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.serialization.json.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keepattributes *Annotation*

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# ONNX Runtime
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class com.squareup.okhttp.** { *; }

# Protocol Buffers
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Remove logging
-assumenosideeffects class timber.log.Timber { public static void v(...); public static void d(...); }
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }

# Optimization
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-verbose
