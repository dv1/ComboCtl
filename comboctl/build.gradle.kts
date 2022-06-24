import org.jetbrains.dokka.gradle.DokkaTask

val kotlinx_coroutines_version = rootProject.extra["kotlinx_coroutines_version"]
val kotlinx_datetime_version = rootProject.extra["kotlinx_datetime_version"]

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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinx_datetime_version")
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
