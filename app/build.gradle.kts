plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livepicture.ar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livepicture.ar"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
        ndk {
            // پردازندهٔ همهٔ گوشی‌های امروزی؛ حجم APK را کم نگه می‌دارد
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ARCore — موتور واقعیت افزوده و ردیابی تصویر (Augmented Images)
    implementation("com.google.ar:core:1.45.0")

    // حالت سازگار برای گوشی‌های بدون ARCore: OpenCV (ردیابی تصویر) + CameraX (دوربین)
    implementation("org.opencv:opencv:4.10.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
}
