plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evolia.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evolia.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signing is driven by environment variables (set in CI from
    // GitHub Secrets). Absent them — local builds and the F-Droid main-repo
    // build — the release stays unsigned and the builder signs it itself.
    signingConfigs {
        create("release") {
            System.getenv("EVOLIA_KEYSTORE_FILE")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("EVOLIA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EVOLIA_KEY_ALIAS") ?: "evolia"
                keyPassword = System.getenv("EVOLIA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("EVOLIA_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
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

    // The supervised binaries ship as lib*.so; legacy packaging keeps them
    // extracted into nativeLibraryDir where they can be exec()'d.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // web3j bundles transitive jars with overlapping META-INF metadata.
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.web3j:core:4.8.7-android")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    testImplementation("junit:junit:4.13.2")
    // Android stubs org.json in unit tests ("not mocked"); use the real impl.
    testImplementation("org.json:json:20231013")
}
