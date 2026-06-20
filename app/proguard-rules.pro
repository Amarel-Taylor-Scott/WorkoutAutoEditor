# MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.protobuf.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.workout.autoeditor.**$$serializer { *; }
-keepclassmembers class com.workout.autoeditor.** {
    *** Companion;
}
-keepclasseswithmembers class com.workout.autoeditor.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Compose tooling
-dontwarn androidx.compose.ui.tooling.**

# Reflection on data classes
-keepclassmembers class com.workout.autoeditor.data.** { *; }
