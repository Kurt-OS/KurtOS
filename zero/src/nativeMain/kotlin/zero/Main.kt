@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package zero

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.system
import spezi.common.CompilationOptions
import spezi.common.diagnostic.Diagnostic
import spezi.common.diagnostic.Level
import spezi.driver.CompilationResult
import spezi.driver.CompilerDriver
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    ZeroCommand().subcommands(BuildCommand()).main(args)
}

private class ZeroCommand : CliktCommand(name = "zero") {
    override fun run() = Unit
}

private class BuildCommand : CliktCommand(name = "build") {
    private val manifestPath by option("--manifest", "-m").default("zero.toml")
    private val outputPath by option("--output", "-o")
    private val target by option("--target", "-t").default("kurt")

    override fun run() {
        val manifestFile = FileSystem.SYSTEM.canonicalize(manifestPath.toPath())
        val manifest = ZeroManifest.read(manifestFile)
        if (manifest == null) {
            echo("zero: manifest not found or invalid: $manifestPath", err = true)
            exitProcess(1)
        }
        if (target !in manifest.targetNames) {
            echo("zero: target '$target' is not declared in ${manifestFile.name}", err = true)
            exitProcess(1)
        }

        val root = manifestFile.parent ?: ".".toPath()
        val source = (root / manifest.source).normalized()
        val output = outputPath?.toPath()?.normalized()
            ?: (root / "build" / "zero" / "${manifest.name}.app").normalized()

        output.parent?.let { if (!FileSystem.SYSTEM.exists(it)) FileSystem.SYSTEM.createDirectories(it) }
        val resolution = GitResolver(root).resolve(manifest)
        if (!resolution.ok) {
            resolution.errors.forEach { echo("zero: $it", err = true) }
            exitProcess(1)
        }
        if (getenv("ZERO_NO_LOCK")?.toKString() != "1") {
            ZeroLock.write(root / "zero.lock", manifest, resolution.locked)
        }

        val options = CompilationOptions(
            inputFiles = listOf(source.toString()),
            outputExe = output.toString(),
            keepIr = false,
            verbose = false,
            optimizationLevel = 0,
            libraries = emptyList(),
            includePaths = (listOf(root) + resolution.includePaths).map { it.toString() },
        )

        val result = CompilerDriver.compile(options)
        result.diagnostics.forEach(::printDiagnostic)
        when (result) {
            is CompilationResult.Success -> {
                echo("zero: built ${manifest.name} for $target -> $output")
            }
            is CompilationResult.Fail -> {
                echo("zero: build failed", err = true)
                exitProcess(1)
            }
        }
    }

    private fun printDiagnostic(diagnostic: Diagnostic) {
        val prefix = when (diagnostic.level) {
            Level.ERROR -> "error"
            Level.WARN -> "warn"
            Level.INFO -> "info"
        }
        val loc = diagnostic.loc
        if (loc == null) {
            echo("$prefix: ${diagnostic.message}", err = diagnostic.level == Level.ERROR)
        } else {
            echo("$prefix: ${loc.source.path}:${loc.line}:${loc.col}: ${diagnostic.message}", err = diagnostic.level == Level.ERROR)
        }
    }
}

private data class ZeroManifest(
    val name: String,
    val version: String,
    val source: String,
    val dependencies: Map<String, DependencySpec>,
    val targetNames: List<String>,
    val targetSpecs: Map<String, DependencySpec>,
) {
    companion object {
        fun read(path: Path): ZeroManifest? {
            if (!FileSystem.SYSTEM.exists(path)) return null

            var section = ""
            var name = ""
            var version = "0.1.0"
            var source = "main.spz"
            val dependencies = mutableMapOf<String, DependencySpec>()
            val targets = mutableListOf<String>()
            val targetSpecs = mutableMapOf<String, DependencySpec>()

            FileSystem.SYSTEM.read(path) { readUtf8() }
                .lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (line.startsWith("[") && line.endsWith("]")) {
                        val header = line.removePrefix("[").removeSuffix("]").trim()
                        if (header.startsWith("\"") && header.endsWith("\"")) {
                            targets.add(header.trim('"'))
                        } else {
                            section = header
                        }
                        return@forEach
                    }

                    val key = line.substringBefore("=", "").trim()
                    val value = line.substringAfter("=", "").trim()
                    if (key.isEmpty()) return@forEach
                    when (section) {
                        "package" -> when (key) {
                            "name" -> name = value.unquote()
                            "version" -> version = value.unquote()
                            "source" -> source = value.unquote()
                        }
                        "dependencies" -> dependencies[key] = DependencySpec.parse(value)
                        "targets" -> parseStringArray(value).forEach {
                            val spec = parseTargetEntry(it)
                            targets.add(spec.first)
                            if (spec.second != null) targetSpecs[spec.first] = spec.second!!
                        }
                        "" -> if (key == "targets") parseStringArray(value).forEach {
                            val spec = parseTargetEntry(it)
                            targets.add(spec.first)
                            if (spec.second != null) targetSpecs[spec.first] = spec.second!!
                        }
                    }
                }

            if (name.isBlank()) return null
            if (targets.isEmpty()) targets.add("kurt")
            return ZeroManifest(name, version, source, dependencies, targets.distinct(), targetSpecs)
        }
    }
}

private data class DependencySpec(
    val git: String,
    val tag: String? = null,
    val rev: String? = null,
    val branch: String? = null,
) {
    val selector: String get() = rev ?: tag ?: branch ?: "HEAD"

    companion object {
        fun parse(value: String): DependencySpec {
            val trimmed = value.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val values = parseInlineTable(trimmed)
                return DependencySpec(
                    git = values["git"] ?: "",
                    tag = values["tag"],
                    rev = values["rev"],
                    branch = values["branch"],
                )
            }

            val raw = trimmed.unquote()
            val split = splitGitSelector(raw)
            return DependencySpec(git = split.first, tag = split.second)
        }
    }
}

private data class LockedDependency(
    val name: String,
    val git: String,
    val selector: String,
    val commit: String,
    val path: Path,
)

private data class Resolution(
    val includePaths: List<Path>,
    val locked: List<LockedDependency>,
    val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

private class GitResolver(private val projectRoot: Path) {
    private val cacheRoot = zeroHome() / "git"

    fun resolve(manifest: ZeroManifest): Resolution {
        val includes = mutableListOf<Path>()
        val locked = mutableListOf<LockedDependency>()
        val errors = mutableListOf<String>()

        manifest.dependencies.forEach { (name, spec) ->
            val resolved = resolveDependency(name, spec)
            if (resolved == null) {
                errors.add("failed to resolve dependency '$name' from ${spec.git}")
            } else {
                includes.add(if (FileSystem.SYSTEM.exists(resolved.path / "src")) resolved.path / "src" else resolved.path)
                locked.add(resolved)
            }
        }
        manifest.targetSpecs.forEach { (name, spec) ->
            val resolved = resolveDependency(name, spec)
            if (resolved == null) {
                errors.add("failed to resolve target '$name' from ${spec.git}")
            } else {
                locked.add(resolved.copy(name = "target:$name"))
            }
        }

        return Resolution(includes, locked, errors)
    }

    private fun resolveDependency(name: String, spec: DependencySpec): LockedDependency? {
        val override = getenv("ZERO_GIT_OVERRIDE_${name.uppercase().replace('-', '_')}")?.toKString()
        val git = override ?: spec.git
        if (git.isBlank()) return null

        if (override != null) {
            val overridePath = git.toPath().normalized()
            if (FileSystem.SYSTEM.exists(overridePath) && !FileSystem.SYSTEM.exists(overridePath / ".git")) {
                return LockedDependency(name, spec.git, "local-override", "local", overridePath)
            }
        }

        val target = cacheRoot / cacheName(git)
        if (!FileSystem.SYSTEM.exists(cacheRoot)) FileSystem.SYSTEM.createDirectories(cacheRoot)
        if (!FileSystem.SYSTEM.exists(target / ".git")) {
            if (!runCommand("git clone ${shellQuote(git)} ${shellQuote(target.toString())}")) return null
        } else {
            runCommand("git -C ${shellQuote(target.toString())} fetch --tags --prune")
        }

        val checkout = spec.rev ?: spec.tag ?: spec.branch
        if (checkout != null) {
            if (!runCommand("git -C ${shellQuote(target.toString())} checkout ${shellQuote(checkout)}")) return null
        }
        val commit = gitHead(target) ?: return null
        return LockedDependency(name, spec.git, spec.selector, commit.trim(), target)
    }

    private fun zeroHome(): Path {
        val env = getenv("ZERO_HOME")?.toKString()
        if (!env.isNullOrBlank()) return env.toPath()
        val home = getenv("HOME")?.toKString() ?: "."
        return (home.toPath() / ".zero").normalized()
    }

    private fun cacheName(git: String): String {
        val clean = git.removeSuffix(".git")
            .replace("://", "_")
            .replace("/", "_")
            .replace(":", "_")
            .replace("@", "_")
        return clean.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}

private object ZeroLock {
    fun write(path: Path, manifest: ZeroManifest, locked: List<LockedDependency>) {
        val builder = StringBuilder()
        builder.appendLine("# Generated by zero. Do not edit by hand.")
        builder.appendLine()
        builder.appendLine("[package]")
        builder.appendLine("name = ${manifest.name.quoteToml()}")
        builder.appendLine("version = ${manifest.version.quoteToml()}")
        locked.forEach {
            builder.appendLine()
            builder.appendLine("[[dependency]]")
            builder.appendLine("name = ${it.name.quoteToml()}")
            builder.appendLine("git = ${it.git.quoteToml()}")
            builder.appendLine("selector = ${it.selector.quoteToml()}")
            builder.appendLine("commit = ${it.commit.quoteToml()}")
        }
        FileSystem.SYSTEM.write(path) { writeUtf8(builder.toString()) }
    }
}

private fun parseStringArray(value: String): List<String> {
    val trimmed = value.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return listOf(trimmed.unquote())
    return trimmed.removePrefix("[").removeSuffix("]")
        .split(",")
        .map { it.trim().unquote() }
        .filter { it.isNotEmpty() }
}

private fun String.unquote(): String = trim().removeSurrounding("\"").removeSurrounding("'")
private fun String.quoteToml(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun parseInlineTable(value: String): Map<String, String> {
    val body = value.trim().removePrefix("{").removeSuffix("}")
    return body.split(",")
        .mapNotNull { part ->
            val key = part.substringBefore("=", "").trim()
            val raw = part.substringAfter("=", "").trim()
            if (key.isEmpty()) null else key to raw.unquote()
        }
        .toMap()
}

private fun splitGitSelector(raw: String): Pair<String, String?> {
    val marker = raw.lastIndexOf('@')
    if (marker <= "https://".length || marker == raw.lastIndex) return raw to null
    return raw.substring(0, marker) to raw.substring(marker + 1)
}

private fun parseTargetEntry(value: String): Pair<String, DependencySpec?> {
    if (!value.contains("://") && !value.endsWith(".git") && !value.contains("@")) return value to null
    val spec = DependencySpec.parse(value)
    return targetNameFromGit(spec.git) to spec
}

private fun targetNameFromGit(git: String): String {
    val last = git.substringAfterLast('/').removeSuffix(".git").removeSuffix(".target")
    return last.ifBlank { "kurt" }
}

private fun runCommand(command: String): Boolean {
    val code = system(command)
    return code == 0
}

private fun gitHead(repo: Path): String? {
    val output = repo / ".zero-head"
    if (!runCommand("git -C ${shellQuote(repo.toString())} rev-parse HEAD > ${shellQuote(output.toString())}")) return null
    val text = FileSystem.SYSTEM.read(output) { readUtf8() }.trim()
    FileSystem.SYSTEM.delete(output)
    return text
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
