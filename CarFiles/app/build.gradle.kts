plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.carfiles"
    compileSdk = 35
    defaultConfig { applicationId = "dev.carfiles"; minSdk = 30; targetSdk = 35; versionCode = 6; versionName = "0.4.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    testImplementation("junit:junit:4.13.2")
}

// Experimental legacy projection is opt-in and never part of the normal APK.
if (providers.gradleProperty("projectionLab").orNull == "true") {
    android.sourceSets.getByName("main").java.srcDir("src/projectionLab/java")
    android.sourceSets.getByName("debug").manifest.srcFile("src/projectionLab/AndroidManifest.xml")
    android.sourceSets.getByName("debug").res.srcDir("src/projectionLab/res")
    android.buildTypes.getByName("debug").applicationIdSuffix = ".lab"
    android.buildTypes.getByName("debug").versionNameSuffix = "-projection-lab"
    dependencies {
        implementation(files("libs/aauto.aar"))
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("androidx.fragment:fragment:1.8.6")
    }
}
