# Room, WorkManager og ML Kit har egne regler i bibliotekene.

# Claude-SDK-et bruker Jackson med refleksjon (Kotlin-modulen) for modellklassene.
# Ubrukte klasser kan fjernes, men de som brukes beholder alle medlemmer og navn.
-keepclassmembers class com.anthropic.** { *; }
-keepnames class com.anthropic.**
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,KotlinMetadata
-dontwarn com.fasterxml.jackson.**
-dontwarn com.github.victools.**
-dontwarn com.standardwebhooks.**
-dontwarn io.swagger.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**
