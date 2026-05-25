import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.PathSensitivity

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
val runtimeSourceDir = project(":runtime").layout.projectDirectory.dir("src/main")
val flxAssetsRoot = layout.projectDirectory.dir("assets")
val userspaceProgramsRoot = layout.projectDirectory.dir("userspace/programs")
val userspacePackageRoot = layout.projectDirectory.dir("userspace/packages")
val generatedUserspaceRoot = layout.buildDirectory.dir("generated/userspace")
val flxStagingRoot = layout.buildDirectory.dir("flx-root")
val flxImage = layout.buildDirectory.file("flx.img")
val flxReservedObjectRecords = 1024
val flxWritableSlackBytes = 1024 * 1024

val zeroBuilder = project(":zero").layout.buildDirectory.file("bin/native/debugExecutable/zero.kexe")
val compileSpeziAppTasks = userspaceProgramsRoot.asFile.listFiles()
    ?.filter { it.isDirectory && it.resolve("main.spz").isFile }
    ?.sortedBy { it.name }
    .orEmpty()
    .map { appDir ->
        val appName = appDir.name
        tasks.register<Exec>("compileSpeziApp${appName.replaceFirstChar { it.uppercase() }}") {
            group = "kurtos"
            description = "Compile userspace/$appName to KurtOS bytecode"
            dependsOn(":zero:linkDebugExecutableNative")

            val source = appDir.resolve("main.spz")
            val manifest = appDir.resolve("zero.toml")
            val output = generatedUserspaceRoot.map { it.file("bin/$appName.app") }

            inputs.file(source)
            inputs.file(manifest)
            inputs.dir(userspacePackageRoot)
            outputs.file(output)

            doFirst {
                output.get().asFile.parentFile.mkdirs()
            }

            executable = zeroBuilder.get().asFile.absolutePath
            args("build", "--manifest", manifest.absolutePath, "--output", output.get().asFile.absolutePath)
            environment("ZERO_GIT_OVERRIDE_LIBKURT", layout.projectDirectory.dir("userspace/packages/libkurt").asFile.absolutePath)
            environment("ZERO_GIT_OVERRIDE_KURT", layout.projectDirectory.dir("targets/kurt").asFile.absolutePath)
            environment("ZERO_NO_LOCK", "1")
        }
    }

val compileSpeziApps by tasks.registering {
    group = "kurtos"
    description = "Compile all Spezi userspace applications"
    dependsOn(compileSpeziAppTasks)
}

data class FlxBuildObject(
    val hash: ByteArray,
    val type: Int,
    val payload: ByteArray,
    val logicalSize: Long,
    var offset: Long = 0L,
)

val buildFlxImage by tasks.registering {
    group = "kurtos"
    description = "Build the FLX content-addressed filesystem image at build/flx.img"

    dependsOn(compileSpeziApps)

    inputs.dir(flxAssetsRoot).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(generatedUserspaceRoot).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(flxImage)

    doLast {
        val staging = flxStagingRoot.get().asFile
        delete(staging)
        copy {
            from(flxAssetsRoot)
            into(staging)
        }
        copy {
            from(generatedUserspaceRoot)
            into(staging)
        }

        val objects = linkedMapOf<String, FlxBuildObject>()

        fun le32(value: Long): ByteArray = byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

        fun writeLe32(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) target[offset + i] = (value shr (i * 8)).toByte()
        }

        fun writeLe64(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = (value shr (i * 8)).toByte()
        }

        fun hashObject(type: Int, payload: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("FLX".toByteArray(Charsets.US_ASCII))
            digest.update(type.toByte())
            digest.update(le32(payload.size.toLong()))
            digest.update(payload)
            return digest.digest()
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun internObject(type: Int, payload: ByteArray, logicalSize: Long = payload.size.toLong()): ByteArray {
            val hash = hashObject(type, payload)
            objects.getOrPut(hex(hash)) { FlxBuildObject(hash, type, payload, logicalSize) }
            return hash
        }

        fun buildBlob(file: File): ByteArray = internObject(1, file.readBytes(), file.length())

        fun buildTree(dir: File): ByteArray {
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                .orEmpty()
            val entries = children.map { child ->
                val type = if (child.isDirectory) 2 else 1
                val hash = if (child.isDirectory) buildTree(child) else buildBlob(child)
                val size = if (child.isDirectory) 0L else child.length()
                Triple(child.name, type, Pair(hash, size))
            }

            val out = ByteArrayOutputStream()
            out.write(le32(entries.size.toLong()))
            entries.forEach { (name, type, hashAndSize) ->
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                out.write(le32(type.toLong()))
                out.write(le32(nameBytes.size.toLong()))
                val sizeBytes = ByteArray(8)
                writeLe64(sizeBytes, 0, hashAndSize.second)
                out.write(sizeBytes)
                out.write(hashAndSize.first)
                out.write(nameBytes)
            }
            return internObject(2, out.toByteArray(), 0L)
        }

        val rootHash = buildTree(staging)
        val records = objects.values.sortedBy { hex(it.hash) }
        val reservedTableRecords = maxOf(flxReservedObjectRecords, records.size)
        var offset = alignUp(512L + reservedTableRecords * 64L, 512L)
        records.forEach { record ->
            record.offset = offset
            offset += record.payload.size
        }
        val finalSize = alignUp(offset + flxWritableSlackBytes, 512L).toInt()
        val image = ByteArray(finalSize)

        "FLX1".toByteArray(Charsets.US_ASCII).copyInto(image, 0)
        writeLe32(image, 4, 1L)
        writeLe32(image, 8, 512L)
        writeLe32(image, 12, records.size.toLong())
        writeLe64(image, 16, 512L)
        writeLe64(image, 24, records.size * 64L)
        rootHash.copyInto(image, 32)
        writeLe64(image, 64, finalSize.toLong())

        records.forEachIndexed { index, record ->
            val base = 512 + index * 64
            record.hash.copyInto(image, base)
            writeLe32(image, base + 32, record.type.toLong())
            writeLe64(image, base + 40, record.offset)
            writeLe64(image, base + 48, record.payload.size.toLong())
            writeLe64(image, base + 56, record.logicalSize)
            record.payload.copyInto(image, record.offset.toInt())
        }

        val output = flxImage.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(image)
        println("FLX image ready: ${output.absolutePath} (${output.length()} bytes, ${records.size} objects)")
    }
}

fun alignUp(value: Long, alignment: Long): Long =
    (value + alignment - 1L) and (alignment - 1L).inv()

val linkKurtOS by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Link runtime objects and the Kotlin kernel static library to build/kurtos.elf"
    dependsOn(":runtime:runtimeObjects", kernelLinkTask)

    inputs.file(linkerScript)
    inputs.file(kernelStaticLib)
    inputs.dir(runtimeObjectsDir)
    outputs.file(layout.buildDirectory.file("kurtos.elf"))

    doFirst {
        val currentRuntimeObjects = runtimeSourceDir.asFile.walkTopDown()
            .filter { it.isFile && (it.extension == "c" || it.extension == "S") }
            .map {
                it.relativeTo(runtimeSourceDir.asFile)
                    .invariantSeparatorsPath
                    .replace('/', '_')
                    .replace(Regex("\\.(c|S)$"), ".o")
            }
            .toSet() + "abi_kotlin_native_stubs.o"
        val runtimeObjects = runtimeObjectsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "o" }
            .filter { it.name in currentRuntimeObjects }
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
    dependsOn(linkKurtOS, buildFlxImage)

    inputs.file(layout.buildDirectory.file("kurtos.elf"))
    inputs.file(flxImage)
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
