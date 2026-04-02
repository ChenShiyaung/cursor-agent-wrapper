import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.cursor.agent"
version = "1.0.0"

val toolchainVersion =
    System.getenv("JDK_TOOLCHAIN_VERSION")?.toIntOrNull()
        ?: run {
            val current = JavaVersion.current().majorVersion.toIntOrNull() ?: 17
            if (current >= 21) 21 else 17
        }

val localIdePath =
    listOf("DEVECO_STUDIO_HOME", "IDEA_HOME")
        .mapNotNull { key -> System.getenv(key) }
        .firstOrNull { path -> File(path).exists() }

repositories {
    maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Prefer a locally installed IDE for realistic plugin debugging.
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            // Fallback so project can build/run on any machine out of the box.
            intellijIdeaCommunity("2024.3.5")
        }
    }

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.49.0.0")
}

kotlin {
    jvmToolchain(toolchainVersion)
    compilerOptions {
        // Keep bytecode at Java 17 so the plugin can run on both JDK 17/21 based IDE runtimes.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(toolchainVersion))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set(provider { null })
    }

    buildSearchableOptions {
        enabled = false
    }
}

configurations {
    runtimeClasspath {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains", module = "annotations")
    }
}
