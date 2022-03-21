plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-kapt")
}

// TODO: It is currently unknown how to get these variables from the comboctl module instead
buildscript {
    extra.apply {
        set("kotlinxCoroutinesVersion", "1.6.0")
        set("kotlinxDatetimeVersion", "0.3.2")
    }
}

val composeVersion = "1.1.1"

dependencies {
    val kotlinxCoroutinesVersion = project.extra["kotlinxCoroutinesVersion"]
    val kotlinxDatetimeVersion = project.extra["kotlinxDatetimeVersion"]
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    implementation(project(":comboctl"))

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDatetimeVersion")
    // This is necessary to avoid errors like these which otherwise come up often at runtime:
    // "WARNING: Failed to transform class kotlinx/datetime/TimeZone$Companion
    // java.lang.NoClassDefFoundError: kotlinx/serialization/KSerializer"
    //
    // "Rejecting re-init on previously-failed class java.lang.Class<
    // kotlinx.datetime.serializers.LocalDateTimeIso8601Serializer>:
    // java.lang.NoClassDefFoundError: Failed resolution of: Lkotlinx/serialization/KSerializer"
    //
    // kotlinx-datetime higher than 0.2.0 depends on kotlinx-serialization, but that dependency
    // is declared as "compileOnly". The runtime dependency on kotlinx-serialization is missing,
    // causing this error. Solution is to add runtimeOnly here.
    //
    // Source: https://github.com/mockk/mockk/issues/685#issuecomment-907076353:
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.3.2")

    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.3")
    implementation("androidx.navigation:navigation-fragment-ktx:2.4.1")
    implementation("androidx.navigation:navigation-ui-ktx:2.4.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.4.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.4.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.4.1")
    implementation("androidx.navigation:navigation-ui-ktx:2.4.1")
    implementation("com.google.android.material:material:1.5.0")

    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.runtime:runtime-livedata:$composeVersion")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.2-beta")
    implementation("androidx.navigation:navigation-compose:2.5.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.4.1")
    implementation("androidx.activity:activity-compose:1.4.0")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.2-beta")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")
}

android {
    compileSdk = 32

    defaultConfig {
        applicationId = "info.nightscout.comboctl.comboandroid"
        minSdk = 28
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        dataBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = composeVersion
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDir("src/main/kotlin") // TODO: not "kotlin.srcDirs" ?
            res.srcDirs(file("src/main/res"))
        }
    }
}
