plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Número de compilación de GitHub (1 en tu ordenador). */
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "es.scala.ambient"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.scala.ambient"
        minSdk = 29
        targetSdk = 35
        // Sube sola en cada compilación de GitHub, para poder instalar encima de la anterior
        versionCode = 100 + buildNumber
        versionName = "1.$buildNumber"
    }

    // Clave de firma fija: todas las versiones llevan la misma firma y se actualizan sin desinstalar.
    // (Repositorio privado. Si lo haces público, cambia la clave y usa secretos de GitHub:
    //  SIGNING_PASSWORD sustituye a la contraseña de abajo.)
    signingConfigs {
        create("release") {
            storeFile = file("signing/scala-ambient.jks")
            storePassword = System.getenv("SIGNING_PASSWORD") ?: "S8jyJRXSWOPmTpbp3sTVbInH"
            keyAlias = "scala"
            keyPassword = System.getenv("SIGNING_PASSWORD") ?: "S8jyJRXSWOPmTpbp3sTVbInH"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Mapa vectorial nítido (MapLibre + estilo oscuro de OpenFreeMap, sin clave de API)
    implementation("org.maplibre.gl:android-sdk:13.6.1")

    // Android Auto: las luces como «app de música» (lo único que Android Auto admite instalado fuera de Play)
    implementation("androidx.media:media:1.7.0")

    // Android Auto (Android for Cars App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
