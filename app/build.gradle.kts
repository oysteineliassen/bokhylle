plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val runNumber = (System.getenv("BYGGNR") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "no.oystein.bokhylle"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.oystein.bokhylle"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Fast nøkkel i repoet slik at nye versjoner kan installeres over den gamle uten å miste bøkene.
    signingConfigs {
        create("app") {
            storeFile = file("bokhylle.keystore")
            storeType = "pkcs12"
            storePassword = "bokhylle"
            keyAlias = "bokhylle"
            keyPassword = "bokhylle"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
            // Bare ARM: alle Android-telefoner bruker ARM, og ML Kits OCR-bibliotek er ~11 MB per arkitektur.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        // Som release (krympet med R8), men med testmottakeren, så røyktesten i CI tester det som faktisk leveres.
        create("qa") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            // Emulatoren i CI er x86_64.
            ndk { abiFilters.clear() }
        }
    }

    packaging {
        // Komprimerte native biblioteker: APK-en blir mye mindre å laste ned (de pakkes ut ved installasjon).
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module", "META-INF/versions/9/module-info.class", "META-INF/FastDoubleParser-*",
                "META-INF/*.version", "META-INF/thirdparty-LICENSE", "META-INF/bigint-LICENSE",
            )
        }
    }

    // Testmottakeren (røyktesten i CI) er bare med i debug-bygget.
    sourceSets {
        // verktoy/tegninger.py legges i figurfilen, så en ny chat kan pakke tegningene uten repoet.
        getByName("main").assets.srcDir("../verktoy")
        for (name in listOf("debug", "qa")) {
            maybeCreate(name).apply {
                java.srcDir("src/testkit/java")
                res.srcDir("src/testkit/res")
                manifest.srcFile("src/testkit/AndroidManifest.xml")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // SVG fra «Tegn figuren på nytt» gjøres om til bilder.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // OCR på telefonen (modellen ligger i appen, virker uten nett) og dokumentskanneren fra Google Play-tjenester.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Claude API (offisiell SDK for Java/Kotlin).
    implementation("com.anthropic:anthropic-java:2.68.0")
    // SDK-et drar inn en eldre kotlin-reflect; bruk samme versjon som resten av Kotlin.
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")

    testImplementation("junit:junit:4.13.2")
}
