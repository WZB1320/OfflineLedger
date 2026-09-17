plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ledger.offline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ledger.offline"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 只打包 arm64-v8a 单 ABI。
        // 现代手机 99% 是 arm64；砍掉其它 ABI 可直接省掉数 MB 的 .so。
        ndk {
            abiFilters += "arm64-v8a"
        }

        resourceConfigurations += setOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                // 不生成原生调试符号
                debugSymbolLevel = "none"
            }
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**"
            )
        }
    }

    // 关闭不必要的 dex 优化产物，进一步压体积
    androidResources {
        generateLocaleConfig = false
    }
}

dependencies {
    // ------------------------------------------------------------------
    // 刻意保持「零第三方运行时依赖」。
    // 没有 Retrofit / OkHttp / Room / Glide / Firebase / 友盟 / Bugly。
    // 任何一条都可能通过 manifest 合并偷偷带进 INTERNET 权限，或带来体积膨胀。
    // ------------------------------------------------------------------
    implementation("androidx.core:core-ktx:1.13.1")               // ~300 KB
    implementation("androidx.appcompat:appcompat:1.7.0")          // ~400 KB
    implementation("androidx.recyclerview:recyclerview:1.3.2")    // ~120 KB
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
