plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


android {
    namespace = "dz.ogefgef322.gnss"

    compileSdk = 35

    defaultConfig {
        applicationId = "dz.ogefgef322.gnss"

        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.3-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            res.srcDir(layout.buildDirectory.dir("generated/res/topoIcon"))
        }
    }
}

val generateTopoLauncher by tasks.registering(Copy::class) {
    from(layout.projectDirectory.file("topo.png"))
    into(layout.buildDirectory.dir("generated/res/topoIcon/mipmap"))
    rename { "topo_launcher.png" }
}

tasks.named("preBuild").configure {
    dependsOn(generateTopoLauncher)
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Activity / permissions helpers
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Lifecycle (optionnel mais propre)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Local broadcast (simple commands between Activities)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Coroutines (si tu utilises des threads / IO NMEA)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Carte OSM (osmdroid)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")



    // CRS / projections (EPSG)
    // Proj4J core + EPSG database to allow factory.createFromName("EPSG:xxxx")
    implementation("org.locationtech.proj4j:proj4j:1.4.1")
    implementation("org.locationtech.proj4j:proj4j-epsg:1.4.1")

}
