package hal

private fun readHeapStart(): ULong =
    BootInfo.heapStart

private fun readHeapEnd(): ULong =
    BootInfo.heapEnd

object Memory {

    val heapStart: ULong by lazy { readHeapStart() }

    val heapEnd: ULong by lazy { readHeapEnd() }

    val heapSize: ULong get() = heapEnd - heapStart

    fun reportHeapInfo() {
        val base = heapStart
        val size = heapSize
        UART.println("Heap: base=0x${base.toString(16)} size=${size} bytes")
    }
}
