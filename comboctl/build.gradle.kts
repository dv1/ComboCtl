import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    `java-library`
    id("org.jetbrains.dokka") version "0.10.1"
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
