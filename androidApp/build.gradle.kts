plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-kapt")
}

val composeVersion = "1.0.5"

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(project(":comboctl"))
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.4.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
    implementation("com.google.android.material:material:1.4.0")

    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.runtime:runtime-livedata:$composeVersion")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.0-beta")
    implementation("androidx.navigation:navigation-compose:2.4.0-beta02")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.4.0")
    implementation("androidx.activity:activity-compose:1.4.0")
    implementation("com.google.accompanist:accompanist-flowlayout:0.21.2-beta")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")
}

android {
    compileSdk = 31
    buildToolsVersion = "30.0.2"

    defaultConfig {
        applicationId = "info.nightscout.comboctl.androidApp"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
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
        kotlinCompilerExtensionVersion = "1.0.5"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDir("src/main/kotlin") // TODO: not "kotlin.srcDirs" ?
            res.srcDirs(file("src/main/res"))
        }
    }
}
