include(":comboctl")
include(":devtools:common")
include(":devtools:dump_packets")
include(":devtools:bluez_cli")
include(":androidApp")

val env = System.getProperties() ?: mapOf<String, String>()

if (env["idea.platform.prefix"] != "AndroidStudio") {
    logger.lifecycle("Not building with Android Studio; enabling javafxApp")
    include(":javafxApp")
    include(":comboctl:src:linuxBlueZCpp")
    include(":comboctl:src:linuxBlueZCpp:external:fmtlib")
    include(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI")
} else
    logger.lifecycle("Building with Android Studio; disabling javafxApp")
