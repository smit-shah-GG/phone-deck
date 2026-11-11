# kotlinx.serialization: keep the generated serializers for our @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.kondraki.archon.** { *** Companion; }
-keepclasseswithmembers class dev.kondraki.archon.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.kondraki.archon.**$$serializer { *; }

# libwebrtc calls into its Java classes from native code by name.
-keep class org.webrtc.** { *; }
