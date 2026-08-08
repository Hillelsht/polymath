# kotlinx.serialization keeps generated serializers reachable through reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.hillelsht.smart.** {
    *** Companion;
}
-keepclasseswithmembers class com.hillelsht.smart.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional platform integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
