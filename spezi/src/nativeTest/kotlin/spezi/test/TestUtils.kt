package spezi.test

import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.common.CompilationOptions
import spezi.driver.CompilationResult
import spezi.driver.CompilerDriver
import kotlin.test.fail

object TestUtils {

    private const val TEST_DIR = "build/test_env"

    fun setup() {
        val path = TEST_DIR.toPath()
        if (FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.deleteRecursively(path)
        }

        FileSystem.SYSTEM.createDirectory(path)
    }

    fun cleanup() {
        val path = TEST_DIR.toPath()
        if (FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.deleteRecursively(path)
        }
    }

    fun compileAndReadBytecode(
        sourceCode: String,
        fileName: String = "main.spz",
        extraFiles: Map<String, String> = emptyMap()
    ): String {
        val srcPath = "$TEST_DIR/$fileName"
        val exePath = "$TEST_DIR/out.app"

        val srcFileObj = srcPath.toPath()
        srcFileObj.parent?.let { if (!FileSystem.SYSTEM.exists(it)) FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(srcFileObj) { writeUtf8(sourceCode) }

        extraFiles.forEach { (name, content) ->
            val p = "$TEST_DIR/$name".toPath()
            p.parent?.let { if (!FileSystem.SYSTEM.exists(it)) FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(p) { writeUtf8(content) }
        }

        val options = CompilationOptions(
            inputFiles = listOf(srcPath),
            outputExe = exePath,
            keepIr = false,
            verbose = false,
            optimizationLevel = 0,
            libraries = emptyList(),
            includePaths = listOf(TEST_DIR)
        )

        val result = CompilerDriver.compile(options)
        if (result !is CompilationResult.Success) fail("Compilation failed for test case: $fileName")

        return FileSystem.SYSTEM.read(exePath.toPath()) { readUtf8() }
    }

    fun assertCompilationFails(sourceCode: String) {
        val srcPath = "$TEST_DIR/fail.spz"
        val exePath = "$TEST_DIR/fail.kexe"
        FileSystem.SYSTEM.write(srcPath.toPath()) { writeUtf8(sourceCode) }

        val options = CompilationOptions(
            inputFiles = listOf(srcPath),
            outputExe = exePath,
            keepIr = false,
            verbose = false,
            optimizationLevel = 0,
            libraries = emptyList(),
            includePaths = listOf(TEST_DIR)
        )

        val result = CompilerDriver.compile(options)
        if (result is CompilationResult.Success) fail("Expected compilation to fail, but it succeeded.")
    }
}
