import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.71"
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
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
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    dependencies {
        // Align versions of all Kotlin components
        implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

        // Use the Kotlin JDK 8 standard library.
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")

        testImplementation("org.junit.jupiter:junit-jupiter:5.6.2")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.2")
    }

    // Show the output of stdout and stderr when running gradle
    // test with --info and --debug.
    tasks.withType<Test> {
        useJUnitPlatform()
        this.testLogging {
            this.showStandardStreams = true
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }

    // Enable more detailed deprecation warnings to be able to pinpoint
    // where exactly deprecated calls are located in the code.
    // Useful when adding third-party code that uses deprecated functions.
    afterEvaluate {
        tasks.withType(JavaCompile::class) {
            options.compilerArgs.add("-Xlint:deprecation")
        }
    }
}
