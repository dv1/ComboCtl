rootProject.name = "ComboCtl"
include(":comboctl")
include(":comboctl:src:linuxBlueZCpp")
include(":comboctl:src:linuxBlueZCpp:external:fmtlib")
include(":comboctl:src:jvmMain:cpp:linuxBlueZCppJNI")
include(":devtools:common")
include(":devtools:dump_packets")
include(":devtools:bluez_cli")

val env = System.getProperties()

if (env["idea.platform.prefix"] != "AndroidStudio") {
    logger.lifecycle("Not building with Android Studio; enabling javafxApp")
    include(":javafxApp")
} else
    logger.lifecycle("Building with Android Studio; disabling javafxApp")
