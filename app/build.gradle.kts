plugins {
    id("com.android.application")
}

// Version comes from the release tag: v1.6.0 -> versionName 1.6.0, versionCode 10600.
// The release workflow passes it as -Pcorkboard.version=<tag>. Local builds get 0.0.0.
val tagVersion: String = (providers.gradleProperty("corkboard.version").orNull ?: "0.0.0").removePrefix("v")
val versionParts: List<Int> = tagVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
val computedVersionCode: Int = (versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]).coerceAtLeast(1)

val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "io.github.nullangst.corkboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nullangst.corkboard"
        minSdk = 29
        targetSdk = 37
        versionCode = computedVersionCode
        versionName = tagVersion
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    lint {
        abortOnError = true
    }

    testOptions {
        // Unit tests run on the JVM against a stub android.jar: plain default values instead of
        // "Stub!" errors. The tests cover pure logic (grid solver, layout repair, layout.json reading).
        unitTests.isReturnDefaultValues = true
    }
}

// Test-only libraries: they never reach the APK. CI fails the build if the release runtime
// classpath holds anything besides the Kotlin standard library (.github/scripts/check_runtime_deps.sh).
dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for tests: Android's copy is only a stub off the device.
    testImplementation("org.json:json:20260814")
}
