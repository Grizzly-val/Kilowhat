# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kilowhat.app.**$$serializer { *; }
-keepclassmembers class com.kilowhat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.kilowhat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
