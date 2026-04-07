# Add project specific ProGuard rules here.
-keep class com.th3cavalry.androidllm.network.dto.** { *; }
-keep class com.th3cavalry.androidllm.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn com.jcraft.**
