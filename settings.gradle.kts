include(":comboctl")

val env = System.getProperties() ?: mapOf<String, String>()

if (env["idea.platform.prefix"] != "AndroidStudio") {
    logger.lifecycle("Not building with Android Studio; enabling BlueZ backend and javafxApp, disabling androidApp")
    include(":javafxApp")
    include(":comboctl:src:linuxBlueZCpp")
    include(":comboctl:src:linuxBlueZCpp:external:fmtlib")
    include(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI")
} else {
    logger.lifecycle("Building with Android Studio; disabling javafxApp, enabling androidApp")
    //include(":androidApp")
}
