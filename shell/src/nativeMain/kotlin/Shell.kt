package shell

import hal.UART

object Shell {

    fun run(dispatch: (String) -> Boolean): Nothing {
        while (true) {
            UART.print("KOS> ")
            val command = UART.readLine()
            if (!dispatch(command)) {
                val trimmed = command.trim()
                if (trimmed.isNotEmpty()) {
                    UART.println("Unknown command: $trimmed. Type 'help'.")
                }
            }
        }
    }
}
