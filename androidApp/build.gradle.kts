val kotlinx_datetime_version: String by rootProject.extra
val android_compile_sdk: Int by rootProject.extra
val android_min_sdk: Int by rootProject.extra
val android_target_sdk: Int by rootProject.extra

val compose_version = "1.1.1"

plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-kapt")
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    implementation(project(":comboctl"))

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinx_datetime_version")
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

    implementation("androidx.compose.ui:ui:$compose_version")
    implementation("androidx.compose.material:material:$compose_version")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose_version")
    implementation("androidx.compose.runtime:runtime-livedata:$compose_version")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.2-beta")
    implementation("androidx.navigation:navigation-compose:2.5.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.4.1")
    implementation("androidx.activity:activity-compose:1.4.0")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.2-beta")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose_version")
}

android {
    compileSdk = android_compile_sdk

    defaultConfig {
        applicationId = "info.nightscout.comboctl.comboandroid"
        minSdk = android_min_sdk
        targetSdk = android_target_sdk
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
        kotlinCompilerExtensionVersion = compose_version
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDir("src/main/kotlin") // TODO: not "kotlin.srcDirs" ?
            res.srcDirs(file("src/main/res"))
        }
    }
}
