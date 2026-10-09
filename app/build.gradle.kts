// Not verified in the original build environment — see README. The app uses no AndroidX libraries,
// so the only dependencies are for JVM unit tests.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "il.hamechutan.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "il.hamechutan.app"
        minSdk = 26
        targetSdk = 34
        val v = java.util.Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
        versionCode = v.getProperty("versionCode").trim().toInt()
        versionName = v.getProperty("versionName").trim()
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.44.1.0")
    testImplementation("org.json:json:20231013") // android.jar's org.json is a stub on the JVM
}
