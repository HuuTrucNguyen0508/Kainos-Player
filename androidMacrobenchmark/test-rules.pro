-keep class ** { *; }
-keepattributes *
-dontoptimize
-dontobfuscate
# Compile-time Error Prone annotations referenced by androidx.test only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
