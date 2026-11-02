plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.carfiles"
    compileSdk = 35
    defaultConfig { applicationId = "dev.carfiles"; minSdk = 30; targetSdk = 35; versionCode = 7; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    testImplementation("junit:junit:4.13.2")
}
