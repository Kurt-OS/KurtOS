plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64("native") {
        binaries {
            executable {
                entryPoint = "zero.main"
                linkerOpts("-z", "muldefs")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":spezi"))
            implementation("com.github.ajalt.clikt:clikt:5.1.0")
            implementation("com.squareup.okio:okio:3.16.4")
        }
    }
}
