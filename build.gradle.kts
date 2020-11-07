import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform") version "1.4.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
}

group = "info.nightscout.comboctl"
version = "1.0-SNAPSHOT"

buildscript {
    repositories {
        jcenter()
        google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.10")
        classpath("com.android.tools.build:gradle:4.1.0")
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
    if (projectPath.any { it.endsWith("-cpp") }) {
        logger.info("Not applying common Java/Kotlin plugins and settings to project \"${project.name}\" (path \"${project.path}\")")
        return@allprojects
    }

    repositories {
        jcenter()
        google()
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
