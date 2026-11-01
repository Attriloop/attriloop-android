plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.attriloop"
version = "0.3.0"

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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            // Obfuscate the AAR host apps consume. Public API is kept via
            // proguard-rules.pro; consumer-rules.pro is what the host's R8 uses.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

// Release unit tests would load the minified class file while still calling
// internal helpers by their source names. Unit tests stay on debug.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "release") {
            variant.enableUnitTest = false
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
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

mavenPublishing {
    coordinates(project.group.toString(), "attriloop-android", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
    // Local Maven smoke tests remain unsigned. The protected release job supplies
    // the in-memory PGP key, which enables signatures for every Central artifact.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("Attriloop Android SDK")
        description.set("Mobile attribution SDK for Attriloop creator and influencer links.")
        inceptionYear.set("2026")
        url.set("https://github.com/Attriloop/attriloop-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("victorgxn")
                name.set("Victor Gonzalez")
                url.set("https://github.com/victorgxn")
            }
        }
        scm {
            url.set("https://github.com/Attriloop/attriloop-android")
            connection.set("scm:git:git://github.com/Attriloop/attriloop-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Attriloop/attriloop-android.git")
        }
    }
}

tasks.matching { it.name.contains("MavenCentral", ignoreCase = true) }.configureEach {
    doFirst {
        require(providers.gradleProperty("signingInMemoryKey").isPresent) {
            "Maven Central publication requires signingInMemoryKey"
        }
    }
}
