plugins {
    kotlin("multiplatform") version "1.4.31" apply false
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
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.31")
        classpath("com.android.tools.build:gradle:4.1.3")
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
        // trove4j is currently not present in mavenCentral. See:
        // https://stackoverflow.com/questions/66049196/replacing-jcenter-in-android-gradle-repositories
        jcenter() {
            content {
                includeModule("org.jetbrains.trove4j", "trove4j")
            }
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
