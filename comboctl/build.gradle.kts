import org.jetbrains.dokka.gradle.DokkaTask

val kotlinx_coroutines_version: String by rootProject.extra
val kotlinx_datetime_version: String by rootProject.extra
val androidx_core_version: String by rootProject.extra
val kotlintest_runner_junit5_version: String by rootProject.extra
val junit_jupiter_engine_version: String by rootProject.extra
val android_compile_sdk: Int by rootProject.extra
val android_min_sdk: Int by rootProject.extra
val android_target_sdk: Int by rootProject.extra

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
        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:$androidx_core_version")
            }
        }
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("io.kotlintest:kotlintest-runner-junit5:$kotlintest_runner_junit5_version")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$junit_jupiter_engine_version")
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
    compileSdk = android_compile_sdk
    defaultConfig {
        minSdk = android_min_sdk
        targetSdk = android_target_sdk
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
