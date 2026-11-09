import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 版本号：versionName 沿用上游 mobile；versionCode = 本仓库 git 提交数
val gitCommitCount: Int = run {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .start()
    val output = process.inputStream.readBytes().decodeToString().trim()
    process.waitFor()
    // 尚无提交时 versionCode 为 0
    output.toIntOrNull() ?: 0
}

android {
    namespace = "tech.touka.diplay.freescale"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.touka.diplay.freescale"
        minSdk = 19
        targetSdk = 19
        versionCode = gitCommitCount
        versionName = "0.2.22"

        vectorDrawables {
            // API 19 无原生 vector 支持，且不引入 appcompat：构建期把 vector 预渲染为 PNG
            generatedDensities?.addAll(listOf("mdpi", "hdpi", "xhdpi", "xxhdpi"))
        }

        ndk {
            // conscrypt-android AAR 自带多 ABI 原生库，仅在最终 APK 保留 armeabi-v7a
            abiFilters.add("armeabi-v7a")
        }
    }

    signingConfigs {
        create("freescale") {
            // 签名凭据仅取环境变量（与上游 mobile 一致），不提供默认值：
            // 缺失时 keystore 指向不存在的文件、密码为空，签名任务直接报错
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            // debug 与 release 均使用飞思卡尔系统签名
            signingConfig = signingConfigs.getByName("freescale")
            // 新增 TLS 栈依赖后方法数超 64K 且禁止 multidex，启用 R8 仅裁剪（不优化不混淆）
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        release {
            // 同 debug：R8 仅裁剪，规则中 -dontoptimize/-dontobfuscate 保持行为不变
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("freescale")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        // 目标平台固定为 API 19，跳过过期 targetSdk/minSdk 检查
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "META-INF/AL2.0"
        resources.excludes += "META-INF/LGPL2.1"
    }
}

// Kotlin 编译目标锁定 JVM 1.8（与 Java compileOptions 一致，API 19 运行时兼容）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// 输出文件命名：DiPlay-<versionName>-<versionCode>-<debug|release>.apk（对应 AGENTS.md 第 10 条，debug/release 分别输出在各自 variant 目录，复制到 dist 时直接使用该文件名）
android.applicationVariants.all {
    val buildTypeName = buildType.name
    outputs.all {
        val output = this as BaseVariantOutputImpl
        output.outputFileName = "DiPlay-$versionName-$versionCode-$buildTypeName.apk"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
}
