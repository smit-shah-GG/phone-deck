// R0 probe build (tasks #118): just enough to prove native WebRTC against the rig's
// aiortc. Phase 1 grows this into the real app (Compose, signing, R8, release.sh).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.kondraki.archon"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kondraki.archon"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-probe"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // dev builds sit beside the real app
            versionNameSuffix = "-dev"
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.webrtc)
    implementation(libs.okhttp)
}
