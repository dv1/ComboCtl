import java.io.ByteArrayOutputStream

plugins {
    `cpp-library`
}

library {
    dependencies {
        implementation(project(":comboctl:src:linuxBlueZCpp"))
    }
}

extensions.configure<CppLibrary> {
    source.from(file("src"))
    privateHeaders.from(file("external/jni-hpp/include"))
}

val glibCflagsStdout = ByteArrayOutputStream()
val glibLibsStdout = ByteArrayOutputStream()

fun getJNICflags(): List<String> {
    val javaHome = System.getenv("JAVA_HOME") ?: throw GradleException(
        "Cannot build JNI bindings without the JAVA_HOME environment variable being present")

    return listOf("-I$javaHome/include", "-I$javaHome/include/linux")
}

fun getGccAndClangCflags(): List<String> {
    return listOf("-Wextra", "-Wall", "-O0", "-g3", "-ggdb", "-fPIC", "-DPIC", "-std=c++17") +
    glibCflagsStdout.toString().trim().split(" ")
}

task<Exec>("glib2PkgConfigCflags") {
    commandLine("pkg-config", "--cflags", "glib-2.0", "gio-2.0")
    standardOutput = glibCflagsStdout
}

task<Exec>("glib2PkgConfigLibs") {
    commandLine("pkg-config", "--libs", "glib-2.0", "gio-2.0")
    standardOutput = glibLibsStdout
}

tasks.withType(CppCompile::class.java).configureEach {
    dependsOn("glib2PkgConfigCflags")
    compilerArgs.addAll(toolChain.map { toolChain ->
        when (toolChain) {
            is Gcc, is Clang -> getGccAndClangCflags() + getJNICflags()
            else -> listOf()
        }
    })
}

tasks.withType(LinkSharedLibrary::class.java).configureEach {
    dependsOn("glib2PkgConfigLibs")
    linkerArgs.addAll(toolChain.map { toolChain ->
        when (toolChain) {
            is Gcc, is Clang -> glibLibsStdout.toString().trim().split(" ")
            else -> listOf()
        }
    })
}
