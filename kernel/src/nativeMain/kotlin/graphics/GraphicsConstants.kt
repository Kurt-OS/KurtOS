package graphics

object GraphicsConstants {

    const val VIRTIO_GPU_DEVICE_ID: UInt = 16u
    const val VIRTIO_GPU_FORMAT_B8G8R8X8_UNORM: UInt = 2u

    const val CMD_GET_DISPLAY_INFO: UInt = 0x0100u
    const val CMD_RESOURCE_CREATE_2D: UInt = 0x0101u
    const val CMD_SET_SCANOUT: UInt = 0x0103u
    const val CMD_RESOURCE_FLUSH: UInt = 0x0104u
    const val CMD_TRANSFER_TO_HOST_2D: UInt = 0x0105u
    const val CMD_RESOURCE_ATTACH_BACKING: UInt = 0x0106u

    const val RESP_OK_NODATA: UInt = 0x1100u
    const val RESP_OK_DISPLAY_INFO: UInt = 0x1101u
    
}