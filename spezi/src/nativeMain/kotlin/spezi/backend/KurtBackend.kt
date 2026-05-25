package spezi.backend

import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.common.Disposable
import spezi.common.diagnostic.CompilerException
import spezi.domain.*

class KurtBackend : Disposable {

    private val out = StringBuilder()
    private var tmpIndex = 0
    private var labelIndex = 0

    override fun dispose() {
        out.clear()
        tmpIndex = 0
        labelIndex = 0
    }

    fun generate(program: Program) {
        out.appendLine("KAPP2")
        program.elements.filterIsInstance<FnDef>().forEach { emitFunction(it) }
    }

    fun emitToFile(path: String) {
        FileSystem.SYSTEM.write(path.toPath()) {
            writeUtf8(out.toString())
        }
    }

    private fun emitFunction(fn: FnDef) {
        if (fn.extensionOf != null) {
            throw CompilerException("KurtOS backend does not support extension functions yet: ${fn.name}")
        }
        if (fn.args.any { it.first.any(Char::isWhitespace) }) {
            throw CompilerException("Invalid argument name in ${fn.name}")
        }

        line("func ${fn.name} ${fn.args.joinToString(" ") { it.first }}")
        emitBlock(fn.body)
        if (fn.retType == Type.Void) line("ret")
        line("end")
    }

    private fun emitBlock(block: Block) {
        block.stmts.forEach { emitStmt(it) }
    }

    private fun emitStmt(stmt: AstNode) {
        when (stmt) {
            is VarDecl -> {
                val init = stmt.init
                if (init == null) {
                    line("lit ${stmt.name} 0")
                } else {
                    line("copy ${stmt.name} ${emitExpr(init)}")
                }
            }

            is Assign -> line("copy ${stmt.name} ${emitExpr(stmt.value)}")

            is IfStmt -> {
                val thenLabel = newLabel()
                val elseLabel = newLabel()
                val endLabel = newLabel()
                line("jnz ${emitExpr(stmt.cond)} $thenLabel $elseLabel")
                line("label $thenLabel")
                emitBlock(stmt.thenBlock)
                line("jmp $endLabel")
                line("label $elseLabel")
                stmt.elseBlock?.let { emitBlock(it) }
                line("label $endLabel")
            }

            is WhileStmt -> {
                val condLabel = newLabel()
                val bodyLabel = newLabel()
                val endLabel = newLabel()
                line("label $condLabel")
                line("jnz ${emitExpr(stmt.cond)} $bodyLabel $endLabel")
                line("label $bodyLabel")
                emitBlock(stmt.body)
                line("jmp $condLabel")
                line("label $endLabel")
            }

            is ReturnStmt -> {
                val value = stmt.value
                if (value == null) line("ret") else line("ret ${emitExpr(value)}")
            }

            is Expr -> emitExpr(stmt)
            else -> {}
        }
    }

    private fun emitExpr(expr: Expr): String {
        return when (expr) {
            is LiteralInt -> newTmp().also { line("lit $it ${expr.value}") }
            is LiteralFloat -> throw CompilerException("KurtOS backend does not support floating point literals yet")
            is LiteralBool -> newTmp().also { line("lit $it ${if (expr.value) 1 else 0}") }
            is LiteralString -> newTmp().also { line("str $it ${hex(expr.value)}") }
            is VarRef -> expr.name

            is UnaryOp -> {
                val target = newTmp()
                line("un $target ${opCode(expr.op)} ${emitExpr(expr.operand)}")
                target
            }

            is BinOp -> {
                val left = emitExpr(expr.left)
                val right = emitExpr(expr.right)
                val target = newTmp()
                line("bin $target ${opCode(expr.op)} $left $right")
                target
            }

            is CastExpr -> emitExpr(expr.expr)

            is Call -> {
                val args = expr.args.map { emitExpr(it) }
                val target = newTmp()
                line("call $target ${expr.name} ${args.size}${if (args.isEmpty()) "" else " ${args.joinToString(" ")}"}")
                target
            }

            is ConstructorCall -> throw CompilerException("KurtOS backend does not support struct construction yet")
            is Access -> throw CompilerException("KurtOS backend does not support struct field access yet")
        }
    }

    private fun opCode(type: TokenType): String = when (type) {
        TokenType.PLUS -> "add"
        TokenType.MINUS -> "sub"
        TokenType.STAR -> "mul"
        TokenType.SLASH -> "div"
        TokenType.PERCENT -> "rem"
        TokenType.EQEQ -> "eq"
        TokenType.NEQ -> "ne"
        TokenType.LESS -> "lt"
        TokenType.LESS_EQ -> "le"
        TokenType.GREATER -> "gt"
        TokenType.GREATER_EQ -> "ge"
        TokenType.AMP -> "and"
        TokenType.PIPE -> "or"
        TokenType.CARET -> "xor"
        TokenType.LSHIFT -> "shl"
        TokenType.RSHIFT -> "shr"
        TokenType.BANG -> "not"
        TokenType.TILDE -> "inv"
        else -> throw CompilerException("Unsupported operator: $type")
    }

    private fun newTmp(): String = "__t${tmpIndex++}"
    private fun newLabel(): String = "L${labelIndex++}"
    private fun line(value: String) {
        out.appendLine(value)
    }

    private fun hex(value: String): String {
        val bytes = value.encodeToByteArray()
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            chars[i * 2] = alphabet[b ushr 4]
            chars[i * 2 + 1] = alphabet[b and 0xf]
            i++
        }
        return chars.concatToString()
    }
}
