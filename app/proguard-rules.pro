# DSH Mobile ProGuard rules
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keep class kotlinx.serialization.** { *; }
-keep class com.dsh.mobile.data.DshProtocol$** { *; }
-keep class com.dsh.mobile.data.RpcEnvelope { *; }
-keep class com.dsh.mobile.data.RpcResult { *; }
-keep class com.dsh.mobile.data.RpcError { *; }
-dontwarn kotlinx.serialization.internal.**

# 应用自身类整体保留，防止 R8 瘦身误删反射/Compose/序列化所用成员（release 包以功能优先）
-keep class com.dsh.mobile.** { *; }

# OkHttp / Okio（远程通道 HTTP + WebSocket 依赖反射与运行时契约）
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.
