package spezi.frontend.semantic

import spezi.domain.FnDef
import spezi.domain.Token
import spezi.domain.Type

class Symbol(
    val name: String,
    val type: Type,
    val isMutable: Boolean,
    val loc: Token,
    var isMoved: Boolean = false,
)

class AnalysisContext {

    private val scopes = ArrayDeque<MutableMap<String, Symbol>>()
    var currentFunction: FnDef? = null

    fun enterScope() = scopes.addFirst(mutableMapOf())
    fun exitScope() = scopes.removeFirst()

    fun define(name: String, type: Type, isMutable: Boolean, loc: Token) {
        scopes.first()[name] = Symbol(name, type, isMutable, loc)
    }

    fun lookup(name: String): Symbol? = scopes.firstNotNullOfOrNull { it[name] }

    fun isDefinedInCurrentScope(name: String) = scopes.first().containsKey(name)
}
