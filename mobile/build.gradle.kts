import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 版本号：versionName 沿用上游 mobile；versionCode = 本仓库 git 提交数 + 1
val gitCommitCount: Int = run {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .start()
    val output = process.inputStream.readBytes().decodeToString().trim()
    process.waitFor()
    // 尚无提交时取 0，versionCode 即为 1
    output.toIntOrNull() ?: 0
}

android {
    namespace = "tech.touka.diplay.freescale"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.touka.diplay.freescale"
        minSdk = 19
        targetSdk = 19
        versionCode = gitCommitCount + 1
        versionName = "0.2.22"

        vectorDrawables {
            // API 19 无原生 vector 支持，且不引入 appcompat：构建期把 vector 预渲染为 PNG
            generatedDensities?.addAll(listOf("mdpi", "hdpi", "xhdpi", "xxhdpi"))
        }
    }

    signingConfigs {
        create("freescale") {
            storeFile = file("D:/AndroidStudioProjects/keystore/mykeys.keystore")
            storePassword = "12345678"
            keyAlias = "freescale"
            keyPassword = "12345678"
        }
    }

    buildTypes {
        debug {
            // debug 与 release 均使用飞思卡尔系统签名
            signingConfig = signingConfigs.getByName("freescale")
        }
        release {
            isMinifyEnabled = false
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

// 输出文件命名：DiPlay-Freescale-<versionName>-<versionCode>-<debug|release>.apk
android.applicationVariants.all {
    outputs.all {
        val output = this as BaseVariantOutputImpl
        output.outputFileName = "DiPlay-Freescale-$versionName-$versionCode-$name.apk"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
}
