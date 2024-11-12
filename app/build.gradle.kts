import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read properties from environment variables first, fallback to local.properties
var googleAppPass: String = System.getenv("GOOGLE_APP_PASS") ?: ""

// Support local development with local.properties, load it conditionally
val propertiesFile = file("$rootDir/local.properties")
if (propertiesFile.exists()) {
    val properties = Properties()
    propertiesFile.inputStream().use { stream ->
        properties.load(stream)
    }
    // Override environment variables with local properties if they exist
    googleAppPass = properties.getProperty("GOOGLE_APP_PASS", googleAppPass)
}

android {
    namespace = "com.jason.vlrs_launcher"

    buildFeatures {
        buildConfig = true
    }

    compileSdk = 34

    defaultConfig {
        applicationId = "com.jason.vlrs_launcher"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Add the GOOGLE_APP_PASS as a build config field
        buildConfigField("String", "GOOGLE_APP_PASS", "\"${googleAppPass}\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packagingOptions {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation ("com.sun.mail:android-mail:1.6.7")
}