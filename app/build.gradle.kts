plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 插件 manifest/注册表用 kotlinx.serialization(依赖已经在 libs 里,core 也在用)
    alias(libs.plugins.kotlin.serialization)
}

// 关于页上的构建编号:当前提交的短哈希;没有 git(如从源码包编译)时写 dev
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    defaultConfig {
        // 复刻版用自己的安装标识：和官方 com.wangzan101.mika 并存，互不覆盖。
        // 注意 namespace 仍是 com.wangzan101.mika（Kotlin 包名，全仓 1000+ 文件），
        // applicationId 与 namespace 本来就允许不同。
        applicationId = "com.yezhong2.mika"
        minSdk = 29
        targetSdk = 35
        // 自己的版本线：从 1 起，不再跟上游（上游是 50 / 0.1.49，闭源发版才 bump）。
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 关于页版本号后的 Beta 标记;出正式版时改成 false
        buildConfigField("boolean", "BETA", "true")
        buildConfigField("boolean", "ADB_LOGS", "false")
    }

    // 沙箱程序只有 arm64 版本(jniLibs/arm64-v8a);APK 不限 ABI,别的设备照常安装、显示「此设备不支持沙箱」。
    // 沙箱程序要从 nativeLibraryDir 直接执行,安装时必须解到磁盘
    packaging { jniLibs { useLegacyPackaging = true } }

    // 发布用的包(perf、release)按架构再出一份:除了全架构包,另出只带 arm64-v8a 原生库的小包。
    // 两个包代码、资源、版本号、签名都一样,只差 lib/ 下的其他架构;各自是完整 APK,不是一起安装的拆分包。
    // 产物:app-universal-<类型>.apk、app-arm64-v8a-<类型>.apk。拆分对所有构建类型生效,
    // 所以只在这次构建要出 perf/release 包时打开,单独构建调试包时产物仍是 app-debug.apk。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    // 自带的运行环境(assets/runtime/rootfs.tar.xz,tools/sandbox/build-runtime.py 生成)已是 xz,不再压一遍
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 评测任务集(仓库根目录 eval/tasks)打进测试包的 assets,真机评测跑器从那里读题
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    // 固定签名：GitHub runner 每次生成的 debug.keystore 都不一样，两次出包互相盖不上
    // （INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能卸载重装、数据全丢）。
    // CI 里把固定 keystore 存成 Actions secret，解到 $RUNNER_TEMP 后用环境变量传进来；
    // 本地没配这几个变量就退回原来的 debug 签名，行为不变。
    signingConfigs {
        val storePath = System.getenv("MIKA_KEYSTORE")
        if (!storePath.isNullOrBlank()) {
            create("fork") {
                storeFile = file(storePath)
                storePassword = System.getenv("MIKA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MIKA_KEY_ALIAS")
                keyPassword = System.getenv("MIKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // 真机验收时并排安装,保留发行版和用户数据;默认构建标识不变。
        if (providers.gradleProperty("devInstall").isPresent) getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Mika Dev")
        }
        // 看流畅度用的包:和发布版一样不可调试(调试包不做预先编译,界面慢好几倍,手感不作数),
        // 用本机调试签名,能直接覆盖调试包、保留数据;可被性能工具采样。./gradlew :app:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            // 有固定 keystore（CI secret）就用它，否则退回本机 debug 签名
            signingConfig = signingConfigs.findByName("fork") ?: signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
            // 真机比较滑动手感也用优化包,仍覆盖 .dev 测试版并保留它的数据。
            if (providers.gradleProperty("devInstall").isPresent) {
                applicationIdSuffix = ".dev"
                resValue("string", "app_name", "Mika Dev")
            }
        }
        // 本地日志检查包：并排安装，允许 adb run-as，首次启动即记录详细 Trace。
        create("logs") {
            initWith(getByName("perf"))
            applicationIdSuffix = ".logs"
            versionNameSuffix = "-logs"
            isDebuggable = true
            isProfileable = false
            resValue("string", "app_name", "Mika Logs")
            buildConfigField("boolean", "ADB_LOGS", "true")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 两种识别模型都随 APK 安装；首次使用不依赖 Play 服务下载。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    // 微信绑定二维码只在本机编码，不调用在线图片服务。
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 沙箱路径契约(core 的 testFixtures):AndroidSandbox 在 JVM 与真机上都跑同一套
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Older transitive Espresso reflects on InputManager.getInstance(), removed on newer devices.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit)
}

// LicensesTest 读仓库的 licenses/THIRD_PARTY_NOTICES.md:让它参与测试的增量判断
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("licenses/THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}
