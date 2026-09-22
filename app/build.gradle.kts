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
        // 侧载自用，没有应用商店帮忙标版本，版本号只能靠自己。
        // 每次「必须让用户确认装的是哪一版」的修复都要动它 —— 同名 APK 覆盖安装时，
        // 系统设置里的版本号是唯一能一眼分辨新旧的地方（02b 就是启动闪退那次修复）。
        versionCode = 8
        versionName = "0.2.5"

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

    // ------------------------------------------------------------------
    // 双版本（productFlavors）
    //
    //   offline —— 不声明 INTERNET，内核层面无法建立 socket。担心任何风险时装这版。
    //   plus    —— 将来声明 INTERNET，但全部网络代码收敛在 net/RuleUpdater.kt 单文件。
    //
    // 两版**共用同一 applicationId 与签名**，可互相覆盖安装、账本数据不丢
    // （因此两版不能同时安装，切换即覆盖 —— 这是刻意的取舍）。
    //
    // ⚠️ 现状（2026-09-17）：只搭了构建骨架，两版**功能完全相同**。
    //    plus 版此刻**不声明 INTERNET、不含 RuleUpdater** ——
    //    在 RuleUpdater 落地前给它网络权限，等于让用户装一个「多要了权限却没有任何
    //    网络功能」的版本，纯风险零收益。方案 §4.7 把 RuleUpdater 划在 P3，
    //    且 P3 处于冻结状态；解冻时做三件事：
    //      ① 新建 app/src/plus/AndroidManifest.xml，只加 <uses-permission INTERNET>
    //      ② 把 net/RuleUpdater.kt 放进 app/src/plus/java/com/ledger/offline/net/
    //      ③ 跑 tools/verify_network_safety.py（同为 P3 产出）
    // ------------------------------------------------------------------
    flavorDimensions += "channel"

    productFlavors {
        create("offline") {
            dimension = "channel"
            versionNameSuffix = "-offline"
            // 让运行时的代码能知道自己是哪一版（将来设置页据此决定显不显示「检查更新」）
            buildConfigField("String", "CHANNEL", "\"offline\"")
            buildConfigField("boolean", "NETWORK_ENABLED", "false")
        }
        create("plus") {
            dimension = "channel"
            versionNameSuffix = "-plus"
            buildConfigField("String", "CHANNEL", "\"plus\"")
            // ⚠️ 仍然是 false：plus 版此刻没有网络代码，也就不该有网络权限。
            //     P3 加入 RuleUpdater 时，这一行与上面的 Manifest 一起改成 true。
            buildConfigField("boolean", "NETWORK_ENABLED", "false")
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

    // ------------------------------------------------------------------
    // 仅测试期依赖，**不进 APK**（testImplementation 只上单测 classpath）。
    //
    // 为什么需要它：Android 的 org.json 在 JVM 单测里是 stub，调用即抛
    // "not mocked"。而 RuleStore 正是用它解析 parser_rules.json ——
    // 结果就是「真实配置 + 真实账单样本」这条组合过去无法被任何测试覆盖，
    // 只能靠手写 profile 副本 + 手写 9 行数据模拟，分布与真实的 132 行差很远。
    //
    // 用 Gson 在测试里解析同一份 assets/parser_rules.json，是把真实配置
    // 拉进回归测试的最低成本做法。
    // ------------------------------------------------------------------
    testImplementation("com.google.code.gson:gson:2.10.1")
}

// ------------------------------------------------------------------
// 让单测真正依赖它读的文件
//
// RealSampleRegressionTest 直接读文件系统上的 assets/parser_rules.json 与
// samples/ 里的真实账单，**不经过 Gradle 的输入跟踪**。Gradle 于是看不见这层
// 依赖：改了规则或换了样本，测试仍被判为 UP-TO-DATE 从而**不重跑**，
// 最终输出 BUILD SUCCESSFUL —— 与「绿色 = 已验证」的假象同源，
// 跟当初 verify_offline.sh「找不到中间产物就跳过、却仍报通过」是同一类错误。
//
// 实测（2026-09-17）：把支付宝档案的 neutralTokens 清空后跑测试，
// 仍是 BUILD SUCCESSFUL in 7s，断言一条都没执行。加下面这段之后才抓得住。
// ------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/assets") { include("*.json") })
        .withPropertyName("testedAssetsJson")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // samples/ 已 gitignore，换台机器可能没有；不存在时不要声明输入，
    // 否则 Gradle 会因「输入缺失」直接失败
    val samplesDir = rootProject.file("samples")
    if (samplesDir.isDirectory) {
        inputs.files(fileTree(samplesDir))
            .withPropertyName("testedBillSamples")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
