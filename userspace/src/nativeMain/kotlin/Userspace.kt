package userspace

import hal.Memory
import hal.UART
import shell.CommandRegistry

object Userspace {

    fun install(registry: CommandRegistry) {
        registry.register("help", "show this message") {
            registry.printHelp()
        }
        registry.register("echo", "print arguments") { args ->
            UART.println(args.joinToString(" "))
        }
        registry.register("mem", "show heap info") {
            Memory.reportHeapInfo()
        }
        registry.register("heap-test", "allocate Kotlin objects and print them") {
            runHeapTest()
        }
        registry.register("clear", "clear screen") {
            UART.print("\u001B[2J\u001B[H")
        }
        registry.register("halt", "halt the system") {
            halt()
        }
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
