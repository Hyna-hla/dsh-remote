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
