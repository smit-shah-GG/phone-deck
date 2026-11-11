# kotlinx.serialization: keep the generated serializers for our @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.kondraki.archon.** { *** Companion; }
-keepclasseswithmembers class dev.kondraki.archon.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.kondraki.archon.**$$serializer { *; }

# libwebrtc calls into its Java classes from native code by name.
-keep class org.webrtc.** { *; }

# Glance creates widget tap handlers by class name (actionRunCallback<T>), through the no-arg
# constructor. R8 kept the class names but stripped the constructors (seen in the release dex,
# 2026-11-11), so every widget key would crash in release builds only.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
# WorkManager builds workers by name too (its own rules cover this; explicit to be safe).
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# libwebrtc's native code also reaches into org.jni_zero (JniZero, @CalledByNative, …) by name.
# The webrtc-sdk AAR ships no consumer rules and nothing in Java references the package, so R8
# deleted all of it: every stream start crashed in release builds only (Pixel, 0.1.75, 2026-11-11).
-keep class org.jni_zero.** { *; }
-keepclasseswithmembers class * { @org.jni_zero.CalledByNative <methods>; }
-keepclasseswithmembers class * { @org.jni_zero.CalledByNativeUnchecked <methods>; }
-keepclasseswithmembers class * { @org.jni_zero.AccessedByNative <fields>; }
# JniZero.setJniClassLoader names JniZeroJni, which the AAR doesn't ship (unused: debug runs without it).
-dontwarn org.jni_zero.JniZeroJni
