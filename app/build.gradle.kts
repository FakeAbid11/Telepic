import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Telegram API credentials come from the environment (GitHub Actions Secrets in CI).
// They are NEVER committed. Debug builds fall back to a safe placeholder (id 0) so CI stays
// green without secrets; real login requires valid values supplied via secrets. A non-numeric
// TELEGRAM_API_ID that is actually provided fails fast rather than silently becoming 0.
val telegramApiIdEnv = System.getenv("TELEGRAM_API_ID")
val telegramApiId = when {
    telegramApiIdEnv.isNullOrBlank() -> 0
    else -> telegramApiIdEnv.toIntOrNull()
        ?: error("TELEGRAM_API_ID must be a numeric integer")
}
val telegramApiHash = System.getenv("TELEGRAM_API_HASH").orEmpty()
// Escape before embedding in the generated Java string literal. The api_hash is 32 hex chars today,
// but an unexpected character must not break (or inject into) the generated BuildConfig.
val telegramApiHashLiteral = telegramApiHash.replace("\\", "\\\\").replace("\"", "\\\"")

// Release signing comes from CI secrets (base64 keystore). A signed release is the default; the
// explicitly named unsigned smoke path exists only to keep R8/resource shrinking exercised in CI.
val releaseKeystoreB64 = System.getenv("RELEASE_KEYSTORE_BASE64").orEmpty()
val allowUnsignedRelease =
    providers.gradleProperty("telepic.allowUnsignedRelease").orNull == "true"

// Release builds must never ship with placeholder credentials, and a distributable release must be
// signed. Fail the moment a release/bundle task is actually requested (debug/test/lint runs stay
// green). The unsigned smoke task opts in explicitly; it is not a distributable artifact.
gradle.taskGraph.whenReady {
    // Match the "release"/"debug" variant suffix case-insensitively (assembleRelease, bundleRelease,
    // lintRelease, minifyReleaseWithR8…), while still excluding "bundleDebugClassesToCompileJar" and
    // friends. A case-sensitive check would miss a task whose name uses a different casing and let a
    // credential-less release slip through the gate below.
    val releaseRequested = allTasks.any {
        it.name.contains("release", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true)
    }
    if (releaseRequested && !allowUnsignedRelease) {
        require(telegramApiId != 0 && telegramApiHash.isNotBlank()) {
            "TELEGRAM_API_ID and TELEGRAM_API_HASH must be provided for release builds"
        }
    }
    // Only packaging consumes the signing config. lint/test release variants do not need a key.
    val releasePackagingRequested = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease"
    }
    if (releasePackagingRequested && !allowUnsignedRelease) {
        require(releaseKeystoreB64.isNotBlank()) {
            "A release build must be signed. Provide RELEASE_KEYSTORE_BASE64, " +
                "RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD, or run " +
                "the explicitly unsigned smoke build with -Ptelepic.allowUnsignedRelease=true."
        }
    }
}

android {
    namespace = "com.telepic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.telepic"
        // Phase 4: the TDLib Android artifact requires minSdk 26 (Android 8.0+).
        minSdk = 26
        // Google Play requires targetSdk 36+ for new submissions (API 36 became mandatory on
        // 2026-08-31); edge-to-edge is enforced there and handled via enableEdgeToEdge +
        // inset-aware scaffolds.
        targetSdk = 36
        // Release CI can supply an explicit version; local/debug builds keep the milestone default.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ARM-only device support for the TDLib native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHashLiteral\"")
    }

    signingConfigs {
        if (releaseKeystoreB64.isNotBlank()) {
            val storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD").orEmpty()
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS").orEmpty()
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD").orEmpty()
            // A half-configured signing config used to produce an empty password/alias and an
            // opaque signing failure. Fail at configuration time with the missing names instead.
            val missing = listOf(
                "RELEASE_KEYSTORE_PASSWORD" to storePassword,
                "RELEASE_KEY_ALIAS" to keyAlias,
                "RELEASE_KEY_PASSWORD" to keyPassword,
            ).filter { it.second.isBlank() }.map { it.first }
            require(missing.isEmpty()) {
                "Release signing is incomplete; missing: ${missing.joinToString()}"
            }
            // CI decodes the base64 secret into build/release.keystore (gitignored).
            val keystoreFile = File(layout.buildDirectory.get().asFile, "release.keystore")
            keystoreFile.parentFile.mkdirs()
            keystoreFile.writeBytes(Base64.getDecoder().decode(releaseKeystoreB64))
            create("release") {
                storeFile = keystoreFile
                storePassword = storePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 shrinks + obfuscates the release artifact and is exercised by the manual
            // `release-smoke` CI job (assembleRelease) — run it before any release attempt. Note what
            // R8 does NOT do: string literals are not obfuscated or encrypted, so the Telegram api_hash
            // below ships as a readable constant in the DEX. That is inherent to TDLib
            // (SetTdlibParameters takes it), not something R8 can fix.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Must resolve after signingConfigs above — the DSL evaluates blocks top-to-bottom.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            // Robolectric needs merged Android resources to run Compose UI tests on the JVM.
            isIncludeAndroidResources = true
        }
    }
}

// With AGP's built-in Kotlin there is no `android { kotlinOptions { } }` block, and no replacement is
// needed: `kotlin.compilerOptions.jvmTarget` defaults to `android.compileOptions.targetCompatibility`,
// which is already Java 17 above.

// Compose compiler reports + metrics. These are the only way to *verify* the performance contract
// rather than infer it: the class report states each type's inferred stability, and the composables
// report states whether a function is `skippable`. Without them, "is LocalMedia stable?" and "can
// MediaTile skip?" are guesses read out of bytecode. CI uploads both as the `telepic-reports`
// artifact, so a regression that reintroduces an unstable parameter is visible in a diff.
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
}

// Room writes a schema JSON per version here during compilation, and those files belong in the repo:
// the committed baseline is what a hand-written future migration is checked against. CI uploads
// `app/schemas/` as the `telepic-room-schemas` artifact so they can be committed without a local
// build. Note what they do *not* do — the migration tests do not read them. Those tests build the
// previous version with raw SQL and then open the real database, so Room's runtime migration
// validation compares the result against the compiled entity definitions. `MigrationTestHelper` is
// not used anywhere in this project.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // App-lock device-credential prompt. MainActivity extends FragmentActivity to host BiometricPrompt.
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.tdlib.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    // Compile Room in the test sources too, so the Phase 6 migration test can build a real
    // version-1 database (TelepicDatabaseV1) and verify the explicit 1 -> 2 migration.
    kspTest(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
