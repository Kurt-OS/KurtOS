plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxArm64 {
        binaries {
            staticLib("kurtos") {
                freeCompilerArgs = freeCompilerArgs + listOf("-Xbinary=gc=noop", "-g")
                if (buildType.name == "RELEASE") {
                    freeCompilerArgs = freeCompilerArgs + "-opt"
                }
            }
        }
    }

    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(project(":hal"))
                implementation(project(":shell"))
            }
        }
    }
}
