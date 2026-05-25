package spezi.driver

import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.backend.KurtBackend
import spezi.common.*
import spezi.common.diagnostic.CompilerException
import spezi.common.diagnostic.Level
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import kotlin.time.measureTime

object CompilerDriver {

    fun compile(options: CompilationOptions): CompilationResult {
        val ctx = Context(options)

        val time = measureTime {
            try {
                val mainFile = options.inputFiles.firstOrNull()
                if (mainFile == null) {
                    ctx.report(Level.ERROR, "No input file provided")
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                if (!FileSystem.SYSTEM.exists(mainFile.toPath())) {
                    ctx.report(Level.ERROR, "Input file not found: $mainFile")
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.currentSource = SourceFile.fromPath(mainFile)
                ctx.isModuleLoaded(mainFile)

                ctx.state = CompilationState.Parsing
                val parser = Parser(ctx)
                val ast = parser.parseProgram()

                if (ctx.reporter.hasErrors) {
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.state = CompilationState.SemanticAnalysis
                val analyzer = SemanticAnalyzer(ctx, ast)
                analyzer.analyze()

                if (ctx.reporter.hasErrors) {
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.state = CompilationState.Codegen
                val backend = KurtBackend()
                val outName = options.outputExe

                try {
                    backend.generate(ast)
                    backend.emitToFile(outName)
                } finally {
                    backend.dispose()
                }

            } catch (e: CompilerException) {
                ctx.report(Level.ERROR, e.message ?: "Unknown compiler error")
                return CompilationResult.Fail(ctx.reporter.diagnostics)
            } catch (e: Exception) {
                ctx.report(Level.ERROR, "Internal Compiler Error: ${e.message}")
                if (options.verbose) e.printStackTrace()
                return CompilationResult.Fail(ctx.reporter.diagnostics)
            }
        }

        return CompilationResult.Success(time, ctx.reporter.diagnostics)
    }
}
