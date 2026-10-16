import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.musicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musicplayer"
        minSdk = 26
        targetSdk = 35
        // versionName must match the pushed git tag (without the "v") — the
        // in-app updater compares this against the latest GitHub release tag.
        versionCode = 10
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    // One permanent release key, so in-app updates can install over the
    // previous version (Android rejects an update signed with a different
    // key). CI supplies it via SIGNING_* env vars (from GitHub secrets);
    // locally it's read from a properties file kept outside the repo.
    // Never commit the keystore or its passwords — losing the key means
    // every user has to uninstall to get future updates.
    val localSigningProps = File(System.getProperty("user.home"), "keystores/music-player-keystore.properties")
        .takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: localSigningProps?.getProperty(prop)
    val releaseStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // Same key as release (when available) so a debug build can be
            // installed over the release app and back without uninstalling —
            // needed for adb run-as access to the app's data.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Paging
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // Coil
    implementation(libs.coil.compose)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // JAudioTagger
    implementation(libs.jaudiotagger)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // DocumentFile (SAF tree traversal)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}
