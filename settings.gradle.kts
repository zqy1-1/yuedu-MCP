pluginManagement {
    buildscript {
        repositories { maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }; mavenCentral(); google(); maven { url = uri("https://storage.googleapis.com/r8-releases/raw") } }
        dependencies { classpath("com.android.tools:r8:9.1.29") }
    }
    repositories {
        // 国内镜像优先，官方源兜底（本机直连 google/mavenCentral 慢，镜像快）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("$rootDir/third_party/maven") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "LegadoSourceStudio"
include(":app")
include(":legado-rhino")
