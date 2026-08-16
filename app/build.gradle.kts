import com.airgate.signing.SignaturePin
import com.airgate.signing.SignaturePinResolver

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

dependencyLocking {
    lockAllConfigurations()
}

val amApplicationId = providers.gradleProperty("AG_APPLICATION_ID")
val amVersionCode = providers.gradleProperty("AG_VERSION_CODE")
val amVersionName = providers.gradleProperty("AG_VERSION_NAME")

android {
    namespace = "com.airgate"
    compileSdk = 37

    defaultConfig {
        applicationId = amApplicationId.get()
        minSdk = 26
        targetSdk = 37
        versionCode = amVersionCode.get().toInt()
        versionName = amVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFileProp = providers.gradleProperty("AG_RELEASE_STORE_FILE")
            if (storeFileProp.isPresent) {
                storeFile = file(storeFileProp.get())
                storePassword = providers.gradleProperty("AG_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("AG_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("AG_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            val pin = SignaturePinResolver.debugPin(signingConfigs.getByName("debug").storeFile)
            when (pin) {
                is SignaturePin.Pinned -> buildConfigField(
                    "String", "EXPECTED_SIGNATURE_HASH", "\"${pin.sha256Hex}\""
                )
                is SignaturePin.Failed -> throw GradleException(pin.reason)
            }
        }

        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = releaseSigning.takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            val pin = SignaturePinResolver.releasePin(
                releaseSigning.storeFile,
                releaseSigning.storePassword,
                releaseSigning.keyAlias,
                releaseSigning.keyPassword
            )
            when (pin) {
                is SignaturePin.Pinned -> buildConfigField(
                    "String", "EXPECTED_SIGNATURE_HASH", "\"${pin.sha256Hex}\""
                )
                is SignaturePin.Failed -> {
                    // A release without its own signing keystore must never ship with
                    // the public debug key pinned or an empty fingerprint. Fail the
                    // release build lazily (only when a release task is actually
                    // requested) so debug builds, unit tests and lint keep working
                    // without a release keystore configured.
                    val reason = pin.reason
                    gradle.taskGraph.whenReady {
                        val buildsRelease = allTasks.any {
                            it.project == project && it.name.contains("Release")
                        }
                        if (buildsRelease) throw GradleException(reason)
                    }
                    // Placeholder that can never match a real signature (fails closed
                    // at runtime); the release build is aborted by the task-graph guard
                    // above before it can be shipped.
                    buildConfigField("String", "EXPECTED_SIGNATURE_HASH", "\"RELEASE_UNCONFIGURED\"")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.dhizuku.api)
    implementation(libs.hiddenapibypass)


    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
