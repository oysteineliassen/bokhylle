# Room, WorkManager og ML Kit har egne regler i bibliotekene.

# Claude-SDK-et bruker Jackson med refleksjon (Kotlin-modulen) for modellklassene.
# Jackson finner (de)serialiserere via refleksjon og annotasjoner; behold hele SDK-et.
-keep class com.anthropic.** { *; }
-keep interface com.anthropic.** { *; }
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
# Bare brukt av SDK-ets klassebaserte skjemagenerering (appen skriver JSON-skjemaet selv); finnes ikke på Android.
-dontwarn java.lang.reflect.AnnotatedType
