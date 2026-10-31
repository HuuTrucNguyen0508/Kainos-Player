# AGP 9.1 uses R8 full mode and integrated resource shrinking by default.
# Serialization, Ktor, SQLDelight, Media3, Coil and Compose supply consumer rules.
# Keep annotation/signature metadata for reflective libraries and generated DTOs.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# librespot instantiates the sink by class name and decoders by public constructor.
# Its protocol messages and Gson models also use reflection. Keep these boundaries
# conservatively until real Spotify playback can be tested on the phone.
-keep class xyz.gianlu.librespot.** { *; }
-keep class com.spotify.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.universalmusic.player.platform.EndSafeNativeDecoder { *; }

# NewPipe evaluates extracted JavaScript with Rhino. Rhino discovers methods and
# JavaScript builtins reflectively, including members not referenced by Java code.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }

# NewPipe's JavaScript helper explicitly uses Context.setInterpretedMode(true).
# These JDK-only Rhino integrations are absent from Android, matching NewPipe's
# own Android rules. Do not suppress unrelated missing dependencies.
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
# Desktop CLI logging inside the bundled librespot artifact is never invoked.
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.core.config.Configurator
