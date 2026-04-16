plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.th3cavalry.androidllm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.th3cavalry.androidllm"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Enable APK split by ABI for smaller downloads
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // litertlm-android is compiled with Kotlin 2.2.x metadata; we suppress the version
        // check since the bytecode is still binary-compatible with our Kotlin 2.0.x compiler.
        freeCompilerArgs = freeCompilerArgs + listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    
    // Encrypted preferences for storing secrets
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Networking: Retrofit + OkHttp for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // On-device LLM inference — MediaPipe LLM Inference API (.task format)
    // Supports Gemma, Phi, Falcon in MediaPipe's LiteRT task format.
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // On-device LLM inference — Google LiteRT-LM (.litertlm format)
    // Google's next-generation on-device LLM SDK (successor to MediaPipe LLM Inference).
    // Supports Gemma 4, Phi-4, Llama, Qwen; GPU/NPU accelerated.
    // Models: https://huggingface.co/litert-community
    // Note: 0.0.0-alpha06 is used for Kotlin 2.0 compatibility; update to 0.9.0+ when upgrading Kotlin.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.0.0-alpha06")

    // On-device LLM inference — Google AI Edge / Gemini Nano (system model, no file needed)
    // Requires Pixel 9+ running Android 15+. The model is managed by AICore on the device.
    // Note: experimental; API may evolve. See https://developer.android.com/ai/gemini-nano
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")

    // SSH client
    implementation("com.github.mwiede:jsch:0.2.17")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
