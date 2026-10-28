// Thin application shell around the :app library module. Everything that only
// makes sense for an installable APK (application ID, signing, launcher entry,
// intent filters) lives here so :app can also be embedded in other apps.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.prajwalch.torrentsearch.standalone"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.prajwalch.torrentsearch"
        minSdk = 25
        targetSdk = 37
        versionCode = property("torrentsearch.versionCode").toString().toInt()
        versionName = property("torrentsearch.versionName").toString()
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("SIGNING_STORE_FILE")?.let(::file)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }

        create("staging") {
            initWith(getByName("release"))

            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid).
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play).
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes += "DebugProbesKt.bin"
        jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":app"))
}
