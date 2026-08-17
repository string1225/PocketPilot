plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.string1225.pocketpilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.string1225.pocketpilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystorePath ?: "build/missing-pocketpilot-release-key.jks")
            storePassword = releaseStorePassword ?: ""
            keyAlias = releaseKeyAlias ?: ""
            keyPassword = releaseKeyPassword ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Pure-Java implementations keep Git and SSH available on Android without
    // relying on binaries provided by the device image.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r")
    implementation(project(":sshj-android"))
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val verifyAgentRuntimeAsset by tasks.registering {
    val runtimeHtml = layout.projectDirectory.file("src/main/assets/pocketpilot-runtime.html")
    val runtimeAsset = layout.projectDirectory.file("src/main/assets/pocketpilot-runtime.js")
    inputs.files(runtimeHtml, runtimeAsset)
    doLast {
        check(runtimeHtml.asFile.isFile) {
            "Missing Android Agent runtime host page."
        }
        check(runtimeAsset.asFile.isFile) {
            "Missing Android Agent runtime bundle. Run `pnpm build` from the repository root."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyAgentRuntimeAsset)
}

val verifyReleaseSigning by tasks.registering {
    notCompatibleWithConfigurationCache(
        "Release signing validation reads process environment and the local keystore path.",
    )
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set ANDROID_RELEASE_KEYSTORE_PATH, " +
                "ANDROID_RELEASE_KEY_ALIAS, ANDROID_RELEASE_STORE_PASSWORD, and " +
                "ANDROID_RELEASE_KEY_PASSWORD."
        }
        check(requireNotNull(releaseKeystorePath).let(::file).isFile) {
            "ANDROID_RELEASE_KEYSTORE_PATH does not point to a release keystore."
        }
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}
