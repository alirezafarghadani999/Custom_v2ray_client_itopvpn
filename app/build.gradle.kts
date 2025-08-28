plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id ("kotlin-parcelize")

}

android {
    namespace = "vpn.vray.itopvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "vpn.vray.itopvpn"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        externalNativeBuild {
            cmake {
                // مسیر CMakeLists.txt شما
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources {
            pickFirsts += "lib/arm64-v8a/libgojni.so"
             pickFirsts += "lib/armeabi-v7a/libgojni.so"
             pickFirsts += "lib/x86/libgojni.so"
             pickFirsts += "lib/x86_64/libgojni.so"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(files("libs/libv2ray.aar"))
    implementation(files("libs/openvpn.aar"))
    implementation(libs.play.services.ads)
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

}

