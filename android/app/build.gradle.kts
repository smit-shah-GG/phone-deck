import java.util.Properties

// Archon: the native Android client for phone-deck (tasks #117). The scaffold follows
// ~/personal/tasks/android: signing from outside the repo, debug builds beside release,
// versionCode = commit count, R8 on release.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val gitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()

// This repo is PUBLIC: the deck's tailnet address and the release key live outside it.
//   ~/.config/archon-android/archon.properties   deckUrl=https://<host>.<tailnet>.ts.net
//   ~/.config/archon-android/keystore.properties storeFile / storePassword / keyAlias / keyPassword
fun props(name: String) = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/archon-android/$name")
    if (f.exists()) f.inputStream().use { load(it) }
}
val local = props("archon.properties")
val keys = props("keystore.properties")
val deckUrl = local.getProperty("deckUrl")
    ?: error("missing deckUrl in ~/.config/archon-android/archon.properties (see app/build.gradle.kts)")

android {
    namespace = "dev.kondraki.archon"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kondraki.archon"
        minSdk = 31
        targetSdk = 37
        versionCode = gitCount
        versionName = "0.1.$gitCount"
        buildConfigField("String", "DECK_URL", "\"${deckUrl.trimEnd('/')}\"")
    }

    signingConfigs {
        create("release") {
            if (keys.isNotEmpty()) {
                storeFile = file(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // dev builds sit beside the real app
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "archon·dev")
        }
        release {
            resValue("string", "app_name", "Archon")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keys.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true; resValues = true; buildConfig = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.webrtc)                  // the R0 probe today; the stream in phase 2
    testImplementation(libs.junit)
}
