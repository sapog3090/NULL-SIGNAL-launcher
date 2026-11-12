plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Только разбор JSON-деревьев (без плагина сериализации): ответы OSINT-сервисов
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
