plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxArm64 {
        compilations["main"].apply {
            cinterops {
                val mmio by creating {
                    definitionFile = file("src/nativeInterop/cinterop/mmio.def")
                    packageName = "mmio"
                }
            }
        }
    }

    sourceSets {
        val linuxArm64Main by getting
    }
}
