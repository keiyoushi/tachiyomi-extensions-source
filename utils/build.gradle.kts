plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "keiyoushi.utils"

    sourceSets {
        named("main") {
            java.setSrcDirs(listOf("src"))
        }
    }

    buildFeatures {
        resValues = false
        shaders = false
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
