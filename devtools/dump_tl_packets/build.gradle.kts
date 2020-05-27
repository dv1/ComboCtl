plugins {
    application
}

// TODO: Add some kind of condition to make sure this
// subproject is only built on PCs, since it makes
// no sense to try to built these for JS, Android etc.

application {
    mainClass.set("devtools.DumpTLPackets")
}

sourceSets {
    main {
        java.srcDir("src")
    }
}

dependencies {
    implementation(project(":devtools:common"))
    implementation(project(":comboctl"))
}

// TODO: Move this code to the root buildscript and refactor
// it to make it reusable, since other subprojects may also
// need to produce standalone JARs, and the only variation
// between subprojects is the Main-Class attribute value.
tasks.jar {
    appendix = "standalone"

    manifest {
        attributes["Main-Class"] = "devtools.DumpTLPacketsKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
