package drivers.input

object InputConstants {

    const val VIRTIO_INPUT_DEVICE_ID: UInt = 18u

    const val VIRTIO_INPUT_CFG_ID_NAME: UByte = 1u

    const val EV_SYN: UShort = 0u
    const val EV_KEY: UShort = 1u
    const val EV_REL: UShort = 2u
    const val EV_ABS: UShort = 3u

    const val REL_X: UShort = 0u
    const val REL_Y: UShort = 1u

    const val ABS_X: UShort = 0u
    const val ABS_Y: UShort = 1u

    const val INPUT_EVENT_SIZE: UInt = 8u
    const val INPUT_EVENT_BUFFERS = 8

    const val BTN_LEFT: UShort = 0x110u
    const val BTN_RIGHT: UShort = 0x111u
    const val BTN_MIDDLE: UShort = 0x112u

    const val KEY_ESC: UShort = 1u
    const val KEY_Q: UShort = 16u
    const val KEY_W: UShort = 17u
    const val KEY_A: UShort = 30u
    const val KEY_S: UShort = 31u
    const val KEY_D: UShort = 32u
    const val KEY_UP: UShort = 103u
    const val KEY_LEFT: UShort = 105u
    const val KEY_RIGHT: UShort = 106u
    const val KEY_DOWN: UShort = 108u

}