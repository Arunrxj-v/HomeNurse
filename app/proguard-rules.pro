# HomeNurse ProGuard rules.

# kotlinx.serialization: keep @Serializable classes and their serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.homenurse.** {
    *** Companion;
}
-keepclasseswithmembers class com.homenurse.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.homenurse.**$$serializer { *; }

# Room entities.
-keep class com.homenurse.data.local.database.entity.** { *; }

# LiteRT-LM and ML Kit are consumed as AARs with their own consumer rules.
