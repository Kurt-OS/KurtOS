package hal

/** Compile-time constants describing the QEMU `virt` AArch64 machine. */
object Platform_QEMU {

    const val UART_BASE: ULong = 0x09000000UL

    const val UART_DR_OFFSET: ULong = 0x00UL

    const val UART_FR_OFFSET: ULong = 0x18UL

    const val UART_FR_RXFE_BIT: UInt = 4u

    const val UART_FR_TXFF_BIT: UInt = 5u

    const val RAM_BASE: ULong = 0x40000000UL

    const val RAM_SIZE: ULong = 134217728UL

    const val RAM_END: ULong = 0x48000000UL

    const val HEAP_SIZE: ULong = 67108864UL

    const val VIRTIO_MMIO_BASE: ULong = 0x0A000000UL

    const val VIRTIO_MMIO_STRIDE: ULong = 0x200UL

    const val VIRTIO_MMIO_COUNT: Int = 32
}
