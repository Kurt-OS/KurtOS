plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxArm64()

    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(project(":hal"))
                implementation(project(":shell"))
            }
        }
    }
}
