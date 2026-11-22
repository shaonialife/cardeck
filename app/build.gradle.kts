import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号约定：四段制 `主.次.修订.内部号`（2026-10-25 起，详见 [appVersionName] 处注释）。
 * 内部测试只动第 4 位；对外发布升第 3 位、第 4 位归 0，展示层不带第 4 位
 * （[displayVersionName]）。
 *
 * `versionCode` 由 `versionName` **推导**而不是手填两处——两者不同步是更新失效的
 * 经典原因（系统只看 versionCode，填小了用户装不上新包，且不会有任何提示）。
 * 发布前只需改 [appVersionName] 这一行。
 *
 * CI（`.github/workflows/android-ci.yml`）每次推送会传
 * `-Pcardeck.buildNumber=<github.run_number>`，构建号顶替第 4 位（见下方 [buildNumber]），
 * 不需要往仓库写提交。
 */
// 版本号四段制（2026-10-25 起）：`主.次.修订.内部号`，当前基线 0.1.28.1（0.1.28 为最后一个对外测试版，下次正式版 0.1.29.0）。
//  - **内部测试：只递增第 4 位**（0.1.28.1 → 0.1.28.2 → …）；
//  - **对外发布：第 3 位 +1、第 4 位归 0**（0.1.29.0），且对外展示不带第 4 位
//    （显示 0.1.29）——见 [displayVersionName] / `BuildConfig.DISPLAY_VERSION`；
//  - `versionCode = (主*10000+次*100+修订)*100000 + 内部号`：对旧三段公式
//    （0.1.41 = 1_410_000）天然反超（0.1.28.1 = 12_800_001），换轨无需跳版本，
//    已装用户可直接覆盖升级。
//  - CI（`.github/workflows/android-ci.yml`）传 `-Pcardeck.buildNumber=<run_number>`
//    时，构建号**顶替第 4 位**（0.1.28.57），无需往仓库写提交。
val appVersionName = "0.1.29.0"
val versionParts =
    appVersionName.split(".").map { part ->
        part.toIntOrNull() ?: error("appVersionName 必须是数字点分（收到 \"$part\"）")
    }
check(versionParts.size == 4) {
    "appVersionName 必须是四段「主.次.修订.内部号」（收到 \"$appVersionName」）"
}
val versionBase = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
val appVersionCode = versionBase * 100_000 + versionParts[3]

/**
 * 预发布构建号（CI 每次推送传 `-Pcardeck.buildNumber=<run_number>`）。
 *
 *  - 构建号**顶替第 4 位**：`0.1.28.1` + N=57 → `0.1.28.57`（内部测试语义一致）；
 *  - `versionCode = base*100000 + N`，同一 base 下随 N 单调递增，可覆盖安装；
 *    base 升修订（0.1.29.0 → 12_900_000）时自动反超本修订所有构建（N < 100000）。
 *
 * **传了构建号就必须有正式 keystore**：否则这个包会被当成「正式版」流出去，
 * 而 debug 证书签的包日后换正式证书时所有用户都得卸载重装。
 */
val buildNumberText =
    providers.gradleProperty("cardeck.buildNumber").orNull?.takeIf { it.isNotBlank() }
val buildNumber = buildNumberText?.toIntOrNull()
check(buildNumberText == null || buildNumber != null) {
    "cardeck.buildNumber 必须是整数（收到 \"$buildNumberText\"）"
}
check(buildNumber == null || buildNumber in 1..99_999) {
    "cardeck.buildNumber 必须在 1..99999（versionCode 的低 5 位留给构建号），当前=$buildNumber"
}
val effectiveVersionName =
    if (buildNumber == null) {
        appVersionName
    } else {
        "${versionParts.take(3).joinToString(".")}.$buildNumber"
    }
val effectiveVersionCode = versionBase * 100_000 + (buildNumber ?: versionParts[3])

/**
 * 对外展示版本：第 4 位为 0（正式版）时只取前三段，否则四段全显。
 * CI 构建号（[buildNumber]）顶替第 4 位时展示同样跟随——否则界面版本
 * 会永远停在 [appVersionName] 静态值，与系统应用信息里的版本脱节
 * （0.1.28.20 装机时界面仍显示 0.1.28.16 的教训，2026-11-02）。
 * 经 `BuildConfig.DISPLAY_VERSION` 暴露给 UI（首页底部版本信息等对外的位置）；
 * 诊断类展示（日志导出、桥 hello）仍用完整 VERSION_NAME（含 flavor 后缀）。
 */
val displayVersionName =
    if (buildNumber != null) {
        effectiveVersionName
    } else if (versionParts[3] == 0) {
        versionParts.take(3).joinToString(".")
    } else {
        appVersionName
    }

/**
 * 发布签名配置：从仓库外的 `keystore.properties` 读取（已在 .gitignore 排除）。
 * 文件缺失时 release 退回 **debug 签名**——只给 fork 者/本地验证用；
 * CI 预发布构建（带 `-Pcardeck.buildNumber`）会直接失败，不会出 debug 签名的「正式版」。
 *
 * 密钥泄露 = 任何人可签发你的更新，因此 keystore 与密码**绝不入库**。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// 预发布/CI 构建（带构建号）禁止落到 debug 签名：宁可构建失败，也不要把一个
// 日后无法被正式包覆盖安装的「正式版」发出去。
check(buildNumber == null || hasReleaseKeystore) {
    "传了 -Pcardeck.buildNumber 但没有 keystore.properties —— 预发布构建禁止使用 debug 签名"
}

android {
    namespace = "io.github.shaonialife.cardeck"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shaonialife.cardeck"
        minSdk = 31
        targetSdk = 34
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
        // 对外展示版本（第 4 位为 0 时不带第 4 位）：不含 flavor/debug 后缀，
        // 给首页底部等面向用户的位置；诊断场景用完整 VERSION_NAME
        buildConfigField("String", "DISPLAY_VERSION", "\"$displayVersionName\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // bridge 维度：standard=日常/发布（包名不变，Shizuku 授权按包名授予勿动）；
    // remote=远程调试版（独立包名 .rd，可与日常版并存安装，调试桥代码只进此变体）
    flavorDimensions += "bridge"
    productFlavors {
        create("standard") {
            dimension = "bridge"
        }
        create("remote") {
            dimension = "bridge"
            applicationIdSuffix = ".rd"
            versionNameSuffix = "-rd"
        }
    }

    buildTypes {
        release {
            // **不做代码混淆**：本项目依赖反射（SystemProperties、Shizuku API）与
            // 自定义 AIDL，R8 需要额外 keep 规则；首版先保证可运行，混淆留待后续。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // **不要加 applicationIdSuffix**：本项目 debug 包就是日常使用的包——
            // Shizuku 授权是按包名授予的，改包名会要求重新授权；AGENTS.md 里的
            // `run-as io.github.shaonialife.cardeck` 调试链路也会一起失效。
            versionNameSuffix = "-debug"
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
        aidl = true
        // remote 变体读 BuildConfig.VERSION_NAME 上报服务器（AGP 8 默认关闭）
        buildConfig = true
    }

    testOptions {
        // 协议层回放测试驱动真实 AirPlayClient，request() 里的 SystemClock 等
        // android 调用在 JVM 上返回默认值即可（否则 "not mocked" 直接炸）
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // 快速集开关（2026-10-25）：-Dtest.quick=true 跳过重放回归（BtLinkReplayTest
            // 单类 240s，占全量 89%）——实验迭代期用，提交前必须跑一次全量（无 -D 即全量）
            if (System.getProperty("test.quick") == "true") {
                test.filter {
                    excludeTestsMatching("io.github.shaonialife.cardeck.replay.*")
                    excludeTestsMatching("io.github.shaonialife.cardeck.airplay.ForbiddenAudiReplayTest")
                }
            }
        }
    }

    lint {
        // 新代码引入 lint 问题（error 级）直接使构建失败；存量问题在基线文件中豁免。
        //  ./gradlew :app:lintDebug          —— 检查（pre-commit 钩子提交前自动执行）
        //  ./gradlew :app:updateLintBaseline —— 修复问题后重建基线（有意豁免时）
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // CarPlay 风格桌面：分页网格
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay 配对密码学：X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512
    // 隐形屏模式：shell 权限（无线调试激活，免 root）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // 本地单测里 Android 的 org.json 是 stub（方法全抛 not mocked）：
    // 用同 API 的参考实现替换（replay fixture 与 nav 解析测试都需要）
    testImplementation("org.json:json:20240303")
}
