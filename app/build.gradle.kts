import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ——————————————————————————————————————————————————————————————
// 发布参数（可选，CI 注入；本地构建不传则保持默认 1.0 / 1）
//   -PversionName=1.2.0  -PversionCode=10200
// GitHub Actions 发布流水线（.github/workflows/release.yml）由 tag/输入解析后传入。
// ——————————————————————————————————————————————————————————————
val releaseVersionName: String? = findProperty("versionName") as String?
val releaseVersionCode: Int? = (findProperty("versionCode") as String?)?.toIntOrNull()

// ——————————————————————————————————————————————————————————————
// 正式发布签名（可选）。未配置任何签名材料时回退 debug 签名，
// release 包可安装验证但不可上架。
// 读取顺序：项目属性 -Psigning.* → 环境变量 SIGNING_*（GitHub Secrets 注入）：
//   keystore：signing.keystoreFile / SIGNING_KEYSTORE_FILE，
//             或 SIGNING_KEYSTORE_B64（keystore 的 base64 内容）
//   口令：    signing.storePassword     / SIGNING_STORE_PASSWORD
//   key：     signing.keyAlias          / SIGNING_KEY_ALIAS
//             signing.keyPassword       / SIGNING_KEY_PASSWORD
// ——————————————————————————————————————————————————————————————
val signingKeystoreFile: File? = run {
    val viaProp = findProperty("signing.keystoreFile") as String?
    val viaEnv = System.getenv("SIGNING_KEYSTORE_FILE")
    val viaB64 = System.getenv("SIGNING_KEYSTORE_B64")
    when {
        viaProp != null -> File(viaProp)
        !viaEnv.isNullOrBlank() -> File(viaEnv)
        !viaB64.isNullOrBlank() -> {
            val target = layout.buildDirectory.file("intermediates/signing/release.jks").get().asFile
            target.parentFile?.mkdirs()
            target.writeBytes(Base64.getDecoder().decode(viaB64.trim()))
            target
        }
        else -> null
    }
}
val signingStorePassword: String? =
    (findProperty("signing.storePassword") as String?) ?: System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyAlias: String? =
    (findProperty("signing.keyAlias") as String?) ?: System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? =
    (findProperty("signing.keyPassword") as String?) ?: System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning: Boolean = signingKeystoreFile != null &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

if (signingKeystoreFile != null && !hasReleaseSigning) {
    logger.warn("MoRead: 检测到 keystore 但签名参数不完整（storePassword/keyAlias/keyPassword 缺失），回退 debug 签名。")
}

android {
    namespace = "com.moread.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moread.app"
        minSdk = 26
        targetSdk = 34
        // 本地默认 1.0 / 1；发布时由 CI 以 -PversionName / -PversionCode 覆盖（见文件顶部）。
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 未注入正式签名材料时回退 debug 签名，保证 release 包可安装验证；
        // 正式上架：由 CI（GitHub Actions Secrets → SIGNING_* 环境变量）或
        // -Psigning.* 项目属性注入独立 keystore 后自动启用 "release" 签名（见文件顶部）。
        if (hasReleaseSigning) {
            create("release") {
                storeFile = signingKeystoreFile!!
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
                enableV1Signing = true
                enableV2Signing = true
            }
        }
        getByName("debug")
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // SPEC §4.2：ABI 分包，arm64-v8a 优先；同时输出 universal 包。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
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
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// SPEC §1.1 依赖白名单：stdlib / RecyclerView / AppCompat / core / coroutines / documentfile。
// 任何新增依赖需评审并回归 APK 体积。
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // SPEC §1.2 备选路线：CommonMark 参考解析器 + GFM 表格扩展。
    // 体积增量约 300KB（DEX 后），仍在 8MB 红线内；本仓库自研解析器保留为容错后备。
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
