import org.jetbrains.dokka.gradle.DokkaTask

buildscript {
    extra.apply {
        set("kotlinxCoroutinesVersion", "1.6.0")
        set("kotlinxDatetimeVersion", "0.3.2")
    }
}

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka") version "0.10.1"
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
    }
    android {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                val kotlinxCoroutinesVersion = project.extra["kotlinxCoroutinesVersion"]
                val kotlinxDatetimeVersion = project.extra["kotlinxDatetimeVersion"]
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDatetimeVersion")
                // Need to use project.dependencies.platform() instead of platform()
                // due to this bug: https://youtrack.jetbrains.com/issue/KT-40489
                implementation(project.dependencies.platform("org.jetbrains.kotlin:kotlin-bom"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")
            }
        }
    }
}

tasks {
    val dokka by getting(DokkaTask::class) {
        outputFormat = "html"
        outputDirectory = "$buildDir/documentation"
        configuration {
            moduleName = "comboctl"
        }
    }
}

android {
    compileSdk = 31
    defaultConfig {
        minSdk = 28
        targetSdk = 28
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
