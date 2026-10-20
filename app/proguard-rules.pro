# file: app/proguard-rules.pro
# Media3/ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization keeps generated serializers by name
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class tv.opentv.**$$serializer { *; }
-keepclassmembers class tv.opentv.** {
    *** Companion;
}
-keepclasseswithmembers class tv.opentv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Coil
-dontwarn coil.**

# LeakCanary-free build; keep ViewBinding tidy
-keep class tv.opentv.databinding.** { *; }
