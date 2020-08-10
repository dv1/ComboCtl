plugins {
    `cpp-library`
}

library {
    linkage.set(listOf(Linkage.STATIC))
}

extensions.configure<CppLibrary> {
    source.from(file("src"))
    publicHeaders.from(file("include"))
}

tasks.withType(CppCompile::class.java).configureEach {
    compilerArgs.addAll(toolChain.map { toolChain ->
        when (toolChain) {
            is Gcc, is Clang -> listOf("-Wextra", "-Wall", "-O0", "-g3", "-ggdb", "-fPIC", "-DPIC")
            else -> listOf()
        }
    })
}
