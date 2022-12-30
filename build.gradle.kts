import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

group = "info.nightscout.comboctl"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlin_version by extra { "1.7.22" }
    val kotlinx_coroutines_version by extra { "1.6.4" }
    val kotlinx_datetime_version by extra { "0.4.0" }
    val androidx_core_version by extra { "1.9.0" }
    val android_gradle_plugin_version by extra { "7.3.1" }
    val ktlint_version by extra { "11.0.0" }
    val kotlintest_runner_junit5_version by extra { "3.4.2" }
    val junit_jupiter_engine_version by extra { "5.9.1" }
    val klaxon_version by extra { "5.6" }
    val android_compile_sdk by extra { 33 }
    val android_min_sdk by extra { 28 }
    val android_target_sdk by extra { 28 }
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:$android_gradle_plugin_version")
        logger.info("Building using Kotlin $kotlin_version")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
    }
}

plugins {
    val kotlin_version = rootProject.extra["kotlin_version"]
    val ktlint_version = rootProject.extra["ktlint_version"]
    kotlin("multiplatform") version "$kotlin_version" apply false
    id("org.jlleitschuh.gradle.ktlint") version "$ktlint_version"
}

ktlint {
    debug.set(true)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

allprojects {
    val projectPath = project.path.split(":")
    if (projectPath.any { it.endsWith("Cpp") }) {
        logger.info("Not applying common Java/Kotlin plugins and settings to project \"${project.name}\" (path \"${project.path}\")")
        return@allprojects
    }

    repositories {
        mavenCentral()
        google()
    }

    // This is a workaround for this incorrect log message until it is fixed in KMM:
    //
    //     The Kotlin source set androidAndroidTestRelease was configured
    //     but not added to any Kotlin compilation. You can add a source
    //     set to a target’s compilation by connecting it with the
    //     compilation’s default source set using ‘dependsOn’.
    //
    // Originally from https://github.com/LouisCAD/Splitties/commit/898e45c9d4db292207d7f83fff8fb3411f81bc4b
    afterEvaluate {
        project.extensions.findByType<KotlinMultiplatformExtension>()?.let { kmpExt ->
            kmpExt.sourceSets.removeAll { it.name == "androidAndroidTestRelease" }
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Show the output of stdout and stderr when running gradle
    // test with --info and --debug.
    tasks.withType<Test> {
        useJUnitPlatform()
        this.testLogging {
            this.showStandardStreams = true
        }
    }
}
