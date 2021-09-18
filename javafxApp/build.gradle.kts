import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.0.9"
}

application {
    mainClass.set("info.nightscout.comboctl.javafxApp.App")
    val rootdir = rootProject.projectDir
    applicationDefaultJvmArgs = listOf(
        "-Djava.library.path=" +
        "$rootdir/comboctl/src/jvmMain/cpp/linuxBlueZCppJNI/build/lib/main/debug"
    )
}

sourceSets {
    main {
        java.srcDir("src/main/kotlin")
    }
    // had to put resources in src/main/resources/:
    // https://stackoverflow.com/a/20677016/560774
}

// Version 5.0.1 of Klaxon is currently (2021-03-04)
// only available in JCenter. Test later if it has
// been copied to maven central.
repositories {
    jcenter() {
        content {
            includeModule("com.beust", "klaxon")
        }
    }
}

javafx {
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web")
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(project(":comboctl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.5.2")
    implementation("com.beust:klaxon:5.0.1")
}

tasks {
    val run by getting {
        dependsOn(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI:build")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
