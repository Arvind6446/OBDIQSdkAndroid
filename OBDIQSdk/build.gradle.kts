plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.cardr.obdiqsdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_KEY", "\"1feddf76-3b99-4c4b-869a-74046daa3e30\"")
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}



publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.cardr.obdiqsdk"
            artifactId = "OBDIQSdk"
            version = "1.0.0"
            artifact("$buildDir/outputs/aar/${artifactId}-release.aar")

            // Add the shadow JAR as the artifact to be published
        }
    }

    repositories {
        maven {
            url = uri("https://jitpack.io")
        }
    }
}




dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    api("com.github.RRCummins:RepairClubAndroidSDK:1.2.21")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.material3:material3")
}
