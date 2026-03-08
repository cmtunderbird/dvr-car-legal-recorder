# DVR Evidence App — ProGuard rules

# Keep session/manifest data classes (serialised to JSON via Gson)
-keep class com.dashcam.dvr.session.** { *; }
-keep class com.dashcam.dvr.evidence.** { *; }

# Keep Gson serialisation
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep CameraX internals
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Android Keystore / Security
-keep class androidx.security.crypto.** { *; }
