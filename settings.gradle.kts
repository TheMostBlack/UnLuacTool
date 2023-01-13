@file:Suppress("UnstableApiUsage")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://repo.gradle.org/gradle/libs-releases")

    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        maven("https://s01.oss.sonatype.org/content/repositories/releases")
        maven("https://jitpack.io")
        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://maven.repository.redhat.com/ga/")
    }
}


rootProject.name = "UnLuacTool"
include(":app")
