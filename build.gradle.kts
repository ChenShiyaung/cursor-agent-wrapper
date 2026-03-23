plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.cursor.agent"
version = "1.0.0"

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
        local("C:/Program Files/Huawei/DevEco Studio 6")
    }

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.49.0.0")
}

kotlin {
    jvmToolchain(17)
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
