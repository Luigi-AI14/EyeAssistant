# ONNX Runtime accede alle proprie classi anche dal codice nativo via JNI.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Google Generative AI SDK
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
