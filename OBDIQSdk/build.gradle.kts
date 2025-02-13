plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.cardr.obdiqsdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_KEY", "\"1feddf76-3b99-4c4b-869a-74046daa3e30\"")
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2024.01.00")) // ✅ BOM for Compose
    api("androidx.compose.material3:material3:1.3.1") // ✅ Stable version
    api("com.github.RRCummins:RepairClubAndroidSDK:1.2.21") // ✅ Ensure it is correct
}

// ✅ Maven Publishing
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.github.Arvind6446"
                artifactId = "OBDIQSdk"
                version = "1.0.0"

                // Automatically locate AAR artifact
                artifact("$buildDir/outputs/aar/${artifactId}-release.aar") {
                    extension = "aar"
                }

                pom {
                    name.set("OBDIQsdk")
                    description.set("Android SDK for OBDIQsdk")
                    url.set("https://github.com/Arvind6446/OBDIQSdkAndroid")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("Arvind6446")
                            name.set("Arvind6446")
                            email.set("gomax6446@gmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/Arvind6446/OBDIQSdkAndroid.git")
                        developerConnection.set("scm:git:ssh://github.com/Arvind6446/OBDIQSdkAndroid.git")
                        url.set("https://github.com/Arvind6446/OBDIQSdkAndroid")
                    }

                    withXml {
                        val dependenciesNode = asNode().appendNode("dependencies")

                        // Adding dependencies manually
                        fun addDependency(group: String, artifact: String, version: String) {
                            val dependencyNode = dependenciesNode.appendNode("dependency")
                            dependencyNode.appendNode("groupId", group)
                            dependencyNode.appendNode("artifactId", artifact)
                            dependencyNode.appendNode("version", version)
                        }

                        addDependency("androidx.compose.material3", "material3", "1.3.1")
                        addDependency("com.github.RRCummins", "RepairClubAndroidSDK", "1.2.21")
                    }
                }
            }
        }
    }
}

// ✅ Register "install" task correctly
tasks.register("install") {
    dependsOn("publishReleasePublicationToMavenLocal")
}
