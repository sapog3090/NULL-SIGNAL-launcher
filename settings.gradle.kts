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
        // только драйверы USB-serial (CH340, CP210x, FTDI, CDC) — их нет в Maven Central
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}

rootProject.name = "NullSignal"
include(":core")
// Android-модуль подключается только если есть SDK (без SDK собирается и тестируется только :core)
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()) {
    include(":app")
}
