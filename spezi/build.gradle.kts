plugins {
    kotlin("multiplatform")
}

group = "org.kvxd"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    linuxX64("native") {
        binaries {
            all {
                linkerOpts("-z", "muldefs")
            }
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation("com.github.ajalt.clikt:clikt:5.1.0")
            implementation("com.squareup.okio:okio:3.16.4")
        }

        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
