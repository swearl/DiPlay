pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 精简移植：仅保留 common（UI/清单入口）、shared（协议核心）、mobile（app 壳）三个模块
rootProject.name = "DiPlay-Freescale"
include(":common")
include(":shared")
include(":mobile")
