plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* CI stamps every build with the workflow run number, so each main build is
   a higher versionCode — that's what the in-app update check compares. */
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

/* Human-facing version, computed by the android.yml workflow at build time
   as "<year>.<week>.<patch>" (see that file) and passed in through the
   environment; the same value goes into version.json. Local builds with no
   CI env fall back to "dev". The versionCode above is what the update check
   actually compares. */
val appVersionName = System.getenv("APP_VERSION_NAME") ?: "dev"

android {
    namespace = "dev.vtlinh.ytkids"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.vtlinh.ytkids"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRunNumber
        versionName = appVersionName
    }

    /* one committed key signs every build: Android only installs an update
       over an existing app when signatures match, and CI runners would
       otherwise generate a fresh random debug key per run */
    signingConfigs {
        create("shared") {
            storeFile = file("../signing.p12")
            storePassword = "ytkids"
            keyAlias = "ytkids"
            keyPassword = "ytkids"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.11.0")

    /* The parent-mode gate. Uses whatever the device already has — fingerprint,
       face, PIN, pattern — rather than a secret this app invents and stores. */
    implementation("androidx.biometric:biometric:1.1.0")

    /* Plain JVM unit tests over the pure decision logic — no emulator, no
       Robolectric. Catalog parsing and the id/URL allowlisting are the parts
       where a mistake puts a child in front of the wrong video, so they are
       pulled out of the Android classes specifically to be testable here. */
    testImplementation("junit:junit:4.13.2")

    /* A real org.json for those tests. The platform ships one, so main uses it
       with no dependency, but the android.jar the unit tests compile against is
       a stub whose every method throws — without this the catalog tests would
       exercise nothing. Same reason the tests are worth having: this parser is
       what decides which ids reach the player. */
    testImplementation("org.json:json:20240303")

    /* A real SQLite for the schema tests. Schema.kt has no Android in it, so
       the exact statements the app executes on a device can be executed here
       against a real engine — see SchemaTest. Migrations run once, on the only
       copy of the approved-channel list a parent has. */
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
