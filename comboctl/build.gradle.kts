import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("kotlin-android-extensions")
    id("org.jetbrains.dokka") version "0.10.1"
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    android()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.3")
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
    compileSdkVersion(29)
    defaultConfig {
        minSdkVersion(24)
        targetSdkVersion(29)
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/AndroidManifest.xml")
        }
    }
}
