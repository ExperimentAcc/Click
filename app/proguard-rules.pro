# CameraX uses reflection-free, stable public APIs, but a few internal
# helper classes are touched reflectively. Keep them safe under R8.
-keep,allowobfuscation,allowoptimization class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Camera2 interop touches framework camera2 classes.
-dontwarn android.hardware.camera2.**

# Kotlin metadata / coroutines basics
-keepattributes *Annotation*,InnerClasses,Signature
-keep class kotlin.Metadata { *; }
