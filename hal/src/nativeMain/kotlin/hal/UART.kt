package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.mmio_read32
import mmio.mmio_write32

@OptIn(ExperimentalForeignApi::class)
object UART {

    private fun mmioReadU32(addr: ULong): UInt = mmio_read32(addr)

    private fun mmioWriteU32(addr: ULong, value: UInt) {
        mmio_write32(addr, value)
    }

    private val DR: ULong = Platform_QEMU.UART_BASE + Platform_QEMU.UART_DR_OFFSET
    private val FR: ULong = Platform_QEMU.UART_BASE + Platform_QEMU.UART_FR_OFFSET

    private val TXFF_MASK: UInt get() = 1u shl Platform_QEMU.UART_FR_TXFF_BIT.toInt()
    private val RXFE_MASK: UInt get() = 1u shl Platform_QEMU.UART_FR_RXFE_BIT.toInt()

    fun putChar(c: Char) {
        while (mmioReadU32(FR) and TXFF_MASK != 0u) { /* spin */ }
        mmioWriteU32(DR, c.code.toUInt() and 0xFFu)
    }

    fun print(s: String) {
        for (c in s) putChar(c)
    }

    fun println(s: String) {
        print(s)
        putChar('\r')
        putChar('\n')
    }

    fun println() {
        putChar('\r')
        putChar('\n')
    }

    fun readChar(): Char {
        while (mmioReadU32(FR) and RXFE_MASK != 0u) { /* spin */ }
        return (mmioReadU32(DR) and 0xFFu).toInt().toChar()
    }

    fun tryReadChar(): Char? {
        if (mmioReadU32(FR) and RXFE_MASK != 0u) return null
        return (mmioReadU32(DR) and 0xFFu).toInt().toChar()
    }

    fun readLine(): String {
        val buf = StringBuilder()
        while (true) {
            val c = readChar()
            when {
                c == '\r' || c == '\n' -> {
                    putChar('\r')
                    putChar('\n')
                    return buf.toString()
                }
                c.code == 0x7F || c.code == 0x08 -> {
                    if (buf.isNotEmpty()) {
                        buf.deleteAt(buf.length - 1)
                        putChar('\b')
                        putChar(' ')
                        putChar('\b')
                    }
                }
                c.code < 0x20 -> { /* ignore non-printable control chars */ }
                else -> {
                    buf.append(c)
                    putChar(c)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return buf.toString()
    }
}
