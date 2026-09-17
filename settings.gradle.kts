// 注意：这里配置的是「构建期」依赖源（阿里云镜像，国内拉包更快）。
// 它不会给 App 带来任何联网能力——App 运行期是否联网只取决于 Manifest 里有没有 INTERNET 权限。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "OfflineLedger"
include(":app")
