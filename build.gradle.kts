import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "1.5.30" apply false
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
}

group = "info.nightscout.comboctl"
version = "1.0-SNAPSHOT"

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.30")
        classpath("com.android.tools.build:gradle:4.2.0")
    }
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
