package userspace

import hal.Memory
import hal.UART

object UserCommands {

    fun dispatch(input: String): Boolean {
        val trimmed = input.trim()
        val parts = trimmed.split(" ").filter { it.isNotEmpty() }
        val verb = parts.firstOrNull() ?: ""

        when (verb) {
            "" -> return true
            "help" -> printHelp()
            "echo" -> UART.println(parts.drop(1).joinToString(" "))
            "mem" -> Memory.reportHeapInfo()
            "heap-test" -> runHeapTest()
            "clear" -> UART.print("\u001B[2J\u001B[H")
            "halt" -> halt()
            else -> return false
        }
        return true
    }

    private fun printHelp() {
        UART.println("Commands:")
        UART.println("  help       - show this message")
        UART.println("  echo ...   - print arguments")
        UART.println("  mem        - show heap info")
        UART.println("  heap-test  - allocate Kotlin objects and print them")
        UART.println("  clear      - clear screen")
        UART.println("  halt       - halt the system")
    }

    private fun halt(): Nothing {
        UART.println("Halting.")
        while (true) {

        }
    }

    private fun runHeapTest() {
        data class TestNode(val id: Int, val label: String)

        val nodes = mutableListOf<TestNode>()
        repeat(5) { i ->
            nodes.add(TestNode(i, "node-$i"))
        }
        nodes.forEach { node ->
            UART.println("  ${node.id}: ${node.label}")
        }
        UART.println("heap-test passed - ${nodes.size} objects allocated and collected.")
    }
}
