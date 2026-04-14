# libVLC — keep all JNI and native classes
-keep class org.videolan.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.adagiostream.android.**$$serializer { *; }
-keepclassmembers class com.adagiostream.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.adagiostream.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google Cast
-keep class com.google.android.gms.cast.** { *; }
-keep class com.adagiostream.android.service.player.CastOptionsProvider { *; }

