plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk = 34

    defaultConfig {
        minSdk = 19

        vectorDrawables {
            // API 19 无原生 vector 支持，且不引入 appcompat：构建期把 vector 预渲染为 PNG
            generatedDensities?.addAll(listOf("mdpi", "hdpi", "xhdpi", "xxhdpi"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Kotlin 编译目标锁定 JVM 1.8（与 Java compileOptions 一致，API 19 运行时兼容）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    api(project(":shared"))
    // 更新检查 TLS 栈：独立 Conscrypt + okhttp(OkUrlFactory)，绕开 Android 4.4 系统 HttpsURLConnection 的协议/套件限制
    implementation(libs.okhttp)
    implementation(libs.okhttpUrlConnection)
    implementation(libs.conscrypt)
}
