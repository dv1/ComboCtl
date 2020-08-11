plugins {
    `java-library`
}

sourceSets {
    main {
        java.srcDir("src")
    }
}

dependencies {
    implementation(project(":comboctl"))
    implementation(group = "org.jline", name = "jline", version = "3.16.0")
}
