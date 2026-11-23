plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The owner's keep-alive test build (decision 0011): the foreground notification, its manifest entries and a "-fgs" version
// suffix. Only with -PkeepAliveFgs (any value but "false"); tester and release builds leave it off.
val keepAliveFgs = (findProperty("keepAliveFgs") as String?)?.let { it.isEmpty() || it.toBoolean() } ?: false

android {
    namespace = "app.unrot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.unrot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Where the detection rules are published (GitHub Pages / Cloudflare Pages).
        // Leave blank to use only the rules bundled in assets/rules.json.
        buildConfigField("String", "RULES_URL", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "KEEP_ALIVE_FGS", keepAliveFgs.toString())
            if (keepAliveFgs) versionNameSuffix = "-fgs"
        }
        release {
            // Release never carries the keep-alive code path or its manifest entries (decision 0011).
            buildConfigField("boolean", "KEEP_ALIVE_FGS", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (keepAliveFgs) {
        // Replaces the debug manifest, which is the only one there is for debug (decision 0011).
        sourceSets.getByName("debug").manifest.srcFile("src/keepAliveFgs/AndroidManifest.xml")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.customview:customview:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
