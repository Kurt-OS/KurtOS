plugins {
    base
    kotlin("multiplatform") version "2.3.21" apply false
}

group = "org.kvxd.kurtos"
version = "0.1.0"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

fun findCrossGcc(): String =
    listOf("aarch64-linux-gnu-gcc", "aarch64-none-elf-gcc").firstOrNull { candidate ->
        try {
            ProcessBuilder("which", candidate)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    } ?: "aarch64-linux-gnu-gcc"

fun findCrossObjcopy(gcc: String): String = gcc.replace(Regex("gcc$"), "objcopy")

val linkerScript = file("linker.ld")
val imageBuildType = if (providers.gradleProperty("kurtos.release").orNull == "true") "Release" else "Debug"
val kernelBinaryDir = "kurtos${imageBuildType}Static"
val kernelLinkTask = ":kernel:linkKurtos${imageBuildType}StaticLinuxArm64"
val kernelStaticLib = project(":kernel").layout.buildDirectory.file("bin/linuxArm64/$kernelBinaryDir/libkurtos.a")
val runtimeObjectsDir = project(":runtime").layout.buildDirectory.dir("objects")

val linkKurtOS by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Link runtime objects and the Kotlin kernel static library to build/kurtos.elf"
    dependsOn(":runtime:runtimeObjects", kernelLinkTask)

    inputs.file(linkerScript)
    inputs.file(kernelStaticLib)
    inputs.dir(runtimeObjectsDir)
    outputs.file(layout.buildDirectory.file("kurtos.elf"))

    doFirst {
        val runtimeObjects = runtimeObjectsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "o" }
            .sortedBy { it.relativeTo(runtimeObjectsDir.get().asFile).invariantSeparatorsPath }
            .toList()
        val bootObject = runtimeObjects.firstOrNull { it.name == "arch_aarch64_boot.o" }
            ?: throw GradleException("runtime boot.o was not produced")
        val kotlinLib = kernelStaticLib.get().asFile
        if (!kotlinLib.isFile) {
            throw GradleException("Kotlin/Native kernel library was not produced: ${kotlinLib.absolutePath}")
        }

        val linkerArgs = mutableListOf(
            "-nostdlib", "-static",
            "-T", linkerScript.absolutePath,
            "-o", layout.buildDirectory.file("kurtos.elf").get().asFile.absolutePath,
            bootObject.absolutePath
        )
        linkerArgs.addAll(runtimeObjects.filterNot { it == bootObject }.map { it.absolutePath })
        linkerArgs.add(kotlinLib.absolutePath)
        linkerArgs.add("-lgcc")

        executable = findCrossGcc()
        setArgs(linkerArgs)
    }
}

val buildImage by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "objcopy kurtos.elf to flat binary build/kurtos.img"
    dependsOn(linkKurtOS)

    inputs.file(layout.buildDirectory.file("kurtos.elf"))
    outputs.file(layout.buildDirectory.file("kurtos.img"))

    doFirst {
        val objcopy = findCrossObjcopy(findCrossGcc())
        executable = objcopy
        args(
            "-O", "binary",
            layout.buildDirectory.file("kurtos.elf").get().asFile.absolutePath,
            layout.buildDirectory.file("kurtos.img").get().asFile.absolutePath
        )
    }

    doLast {
        val img = layout.buildDirectory.file("kurtos.img").get().asFile
        if (img.exists())
            println("KurtOS image ready: ${img.absolutePath}  (${img.length()} bytes)")
    }
}

tasks.named("assemble") {
    dependsOn(buildImage)
}
