import java.io.ByteArrayOutputStream

plugins {
    `cpp-library`
}

library {
    dependencies {
        implementation(project(":platform-linux:linux-bluez-cpp:external:fmtlib"))
    }
}

extensions.configure<CppLibrary> {
    source.from(file("src"))
    privateHeaders.from(file("src/priv-headers"), file("external/jni-hpp/include"))
    publicHeaders.from(file("include"))
}

val glibCflagsStdout = ByteArrayOutputStream()
val glibLibsStdout = ByteArrayOutputStream()

fun getGccAndClangCflags(): List<String> {
    return listOf("-Wextra", "-Wall", "-O0", "-g3", "-ggdb", "-fPIC", "-DPIC", "-std=c++17") +
    glibCflagsStdout.toString().trim().split(" ") +
    listOf("-I/usr/lib/jvm/java-11-openjdk-amd64/include", "-I/usr/lib/jvm/java-11-openjdk-amd64/include/linux")
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
            // TODO: Remove hardcoded JNI include paths
            is Gcc, is Clang -> getGccAndClangCflags()
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
