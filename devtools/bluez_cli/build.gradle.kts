import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm")
}

// TODO: Add some kind of condition to make sure this
// subproject is only built on PCs, since it makes
// no sense to try to built these for JS, Android etc.

application {
    mainClass.set("devtools.BlueZCLI")
}

sourceSets {
    main {
        java.srcDir("src")
    }
}

dependencies {
    implementation(project(":comboctl"))
    implementation(project(":devtools:common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.0")
}

// TODO: Move this code to the root buildscript and refactor
// it to make it reusable, since other subprojects may also
// need to produce standalone JARs, and the only variation
// between subprojects is the Main-Class attribute value.
tasks.jar {
    appendix = "standalone"

    manifest {
        attributes["Main-Class"] = "devtools.BlueZCLIKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

tasks {
    val run by getting {
        dependsOn(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI:build")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
