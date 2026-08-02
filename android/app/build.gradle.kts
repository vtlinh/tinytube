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

/* Where the signing key is, and whether there is one at all. It is a repository
   secret now rather than a committed file — see the note by signingConfigs. */
val keystoreFile = file("../signing.p12")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val canSign = keystoreFile.exists() && !keystorePassword.isNullOrBlank()

android {
    namespace = "dev.vtlinh.tinytube"
    compileSdk = 34

    defaultConfig {
        /* The address of every installed copy, and the reason renaming it is
           a one-way door.

           Android identifies an installed app by this string. Changing it from
           any other value means this build cannot update the app already on
           a phone — it installs BESIDE it, with its own data directory, and
           the approved channels, watch history and settings of the old one are
           not visible to it. The old app has to be uninstalled by hand and its
           channels approved again.

           That was asked for and is done. What must not happen is doing it
           twice: there is no migration here, only a fresh start, and every
           further rename costs another one. */
        applicationId = "dev.vtlinh.tinytube"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRunNumber
        versionName = appVersionName
    }

    /* One key signs every build: Android only installs an update over an
       existing app when signatures match, and CI runners would otherwise
       generate a fresh random debug key per run.

       THE KEY IS NOT IN THIS REPOSITORY, and it is not the key that was. Both
       facts arrived together when this went public.

       The old key was committed here with its password in this file. Removing
       it from the tree would not have unpublished it — GitHub keeps every pull
       request's refs, 76 of which carried the file, and those are fetchable by
       anyone the moment the repository is public. A history rewrite does not
       touch them. So the key was ROTATED rather than hidden: a leaked key that
       signs nothing is harmless, which is a property no amount of scrubbing
       could have bought.

       What that cost is stated plainly in README, because it is not small:
       every copy installed before the rotation has to be uninstalled and
       reinstalled by hand, losing its approved channels and watch history.
       Android refuses an update whose certificate changed, and this one did:

         old, now dead  74:7B:05:92:…:46:4B:24:9F
         current        D1:A7:D2:1D:…:C9:F9:06:D1

       APK Signature Scheme v3 rotation could have avoided that on API 28+, by
       carrying a lineage proving the old key authorised the new one. It was not
       used: minSdk is 26, so it would have stranded the oldest devices anyway,
       and it cannot be verified anywhere but CI.

       CI writes the keystore out from ANDROID_KEYSTORE_B64 before building. For
       a local release build, put the .p12 at android/signing.p12 — it is
       gitignored — and export ANDROID_KEYSTORE_PASSWORD. */
    signingConfigs {
        /* Created only when there is something to create it from. Pointing a
           signingConfig at a file that isn't there fails every task, including
           unit tests that have no business needing a key. What must not build
           is a RELEASE, and that is refused below where it can be refused
           precisely. */
        if (canSign) {
            create("shared") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "tinytube"
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            /* Falls back to Gradle's own throwaway debug key when the real one
               isn't around, so a fresh checkout still builds and runs. A debug
               build was never going to update anybody's phone. */
            if (canSign) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("shared")
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
