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
}
