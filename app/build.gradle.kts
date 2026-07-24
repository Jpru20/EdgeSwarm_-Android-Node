plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val edgeApiBaseUrl =
    System.getenv("EDGESWARM_API_BASE_URL")
        ?: "https://api.edgeswarm.io"

val edgeSupabaseUrl =
    System.getenv("EDGESWARM_SUPABASE_URL")
        ?: "https://xrmwmoqgukjztboemvgi.supabase.co"

val edgeSupabaseAnonKey =
    System.getenv("EDGESWARM_SUPABASE_ANON_KEY")
        ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhybXdtb3FndWtqenRib2VtdmdpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MzgzNDcsImV4cCI6MjA5NTMxNDM0N30.3kP1uRFgRAgr2L2eh3Su36icRUHMEsfYIJc1RBV1jjM"

val edgeReleaseChannel =
    System.getenv("EDGESWARM_ANDROID_RELEASE_CHANNEL")
        ?: "public_beta"

android {
    namespace = "com.edgeswarm.node"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgeswarm.node"
        minSdk = 26
        targetSdk = 35
        versionCode = 159
        versionName = "1.5.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "long",
            "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
            "1079897740623L"
        )
        buildConfigField(
            "String",
            "API_BASE_URL",
            edgeApiBaseUrl.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "SUPABASE_URL",
            edgeSupabaseUrl.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            edgeSupabaseAnonKey.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "RELEASE_CHANNEL",
            edgeReleaseChannel.asBuildConfigString()
        )
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                System.getenv("EDGESWARM_UPLOAD_KEYSTORE")
                    ?: "keys/edgeswarm-upload-key.jks"
            )
            storePassword =
                System.getenv("EDGESWARM_UPLOAD_STORE_PASSWORD") ?: ""
            keyAlias =
                System.getenv("EDGESWARM_UPLOAD_KEY_ALIAS")
                    ?: "edgeswarm-upload"
            keyPassword =
                System.getenv("EDGESWARM_UPLOAD_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes +=
                "META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,license.txt," +
                "NOTICE,NOTICE.txt,notice.txt,ASL2.0,*.kotlin_module}"
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    implementation("com.google.android.play:integrity:1.4.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("org.web3j:core:4.8.7-android")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(
        platform("io.github.jan-tennert.supabase:bom:3.0.1")
    )
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation(
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3"
    )
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
}
