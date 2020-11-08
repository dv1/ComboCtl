import java.io.ByteArrayOutputStream

plugins {
    `cpp-library`
}

library {
    linkage.set(listOf(Linkage.STATIC))
    dependencies {
        api(project("external:fmtlib"))
    }
}

extensions.configure<CppLibrary> {
    source.from(file("src"))
    privateHeaders.from(file("src/priv-headers"))
    publicHeaders.from(file("include"))
}

val glibCflagsStdout = ByteArrayOutputStream()
val glibLibsStdout = ByteArrayOutputStream()

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
