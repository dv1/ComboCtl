import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.0.9"
}

application {
    mainClass.set("info.nightscout.comboctl.javafxApp.Application")
    val rootdir = rootProject.projectDir
    // Add the path to the linuxBlueZCpp .so that contains
    // the BlueZ Bluetooth backend that is used on the PC.
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

javafx {
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(project(":comboctl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.5.2")
    implementation("com.beust:klaxon:5.5")
}

tasks {
    val run by getting {
        dependsOn(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI:build")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}
