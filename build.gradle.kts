plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("maven-publish")
}

// JitPack publishes under the repo owner's group. See README.md.
group = "com.github.victorgxn"
version = "0.1.0"

android {
    namespace = "io.attriloop.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Expose a single `release` software component so maven-publish can publish the AAR
    // (required by AGP 7.1+ before `components["release"]` exists).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Deterministic Android attribution: reads the Play Store `referrer` the
    // redirect edge stamps with our click id.
    implementation("com.android.installreferrer:installreferrer:2.2")

    // Unit tests. org.json ships in android.jar but is stubbed (throws) for local
    // unit tests, so pull the real implementation onto the test classpath.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Publication consumed by JitPack:
//   implementation("com.github.victorgxn:attriloop-android:<tag>")
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.victorgxn"
            artifactId = "attriloop-android"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
