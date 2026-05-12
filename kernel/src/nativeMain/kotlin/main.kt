package kernel

import hal.Memory
import hal.UART
import kernel.fdt.DeviceTree
import kernel.graphics.KernelGraphicsCommands
import kernel.memory.PageAllocator
import shell.Shell
import userspace.UserCommands
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

@OptIn(ExperimentalNativeApi::class)
@CName("kotlin_main")
fun main() {
    UART.println("KurtOS booting...")
    UART.println("HAL initialized.")

    Memory.reportHeapInfo()
    val deviceTree = DeviceTree.current()
    PageAllocator.initialize(deviceTree?.range)
    if (deviceTree == null) {
        UART.println("FDT: unavailable")
    } else {
        UART.println("FDT: base=0x${deviceTree.range.start.toString(16)} size=${deviceTree.totalSize} bytes")
    }
    UART.println("Pages: base=0x${PageAllocator.managedRange.start.toString(16)} size=${PageAllocator.managedRange.size} bytes")
    KernelGraphicsCommands.initialize(deviceTree)

    Shell.run { command ->
        if (command.trim() == "help") {
            UserCommands.dispatch(command)
            KernelGraphicsCommands.printHelp()
            true
        } else {
            UserCommands.dispatch(command) || KernelGraphicsCommands.dispatch(command)
        }
    }
}
