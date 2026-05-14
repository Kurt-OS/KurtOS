package shell

import hal.UART
import drivers.DeviceManager
import drivers.input.InputService
import fdt.DeviceTree
import fs.FlxEntryKind
import fs.FlxService
import applications.SnakeApplication
import graphics.GraphicsService

object KernelShell {
    fun install(registry: CommandRegistry, deviceTree: DeviceTree?) {
        registry.register("devices", "list discovered hardware, drivers, and capabilities") {
            DeviceManager.initialize(deviceTree)
            DeviceManager.printSummary()
        }
        registry.register("virtio-scan", "list visible virtio-mmio devices") {
            DeviceManager.initialize(deviceTree)
            val devices = DeviceManager.virtioDevices()
            if (devices.isEmpty()) {
                UART.println("Virtio: no MMIO devices visible")
            } else {
                devices.forEach { device ->
                    UART.println(
                        "Virtio: id=${device.deviceId} version=${device.version} base=0x${
                            device.base.toString(
                                16
                            )
                        }"
                    )
                }
            }
        }
        registry.register("gfx-info", "show graphics device status") {
            val ok = GraphicsService.initialize(deviceTree)
            UART.println("Graphics: ${GraphicsService.status()} (${if (ok) "online" else "offline"})")
        }
        registry.register("gfx-clear", "clear framebuffer; optional hex color") { args ->
            val color = args.getOrNull(0)?.toUIntOrNull(16) ?: 0x004080c0u
            if (GraphicsService.initialize(deviceTree)) {
                GraphicsService.framebuffer()?.clear(color)
                GraphicsService.framebuffer()?.present()
                UART.println("Graphics cleared.")
            } else {
                UART.println("Graphics: ${GraphicsService.status()}")
            }
        }
        registry.register("gfx-test", "draw a framebuffer test pattern") {
            runGraphicsTest(deviceTree)
        }
        registry.register("input-info", "show keyboard/mouse status") {
            val ok = InputService.initialize(deviceTree)
            UART.println("Input: ${InputService.status()} (${if (ok) "online" else "offline"})")
        }
        registry.register("input-poll", "poll and print queued input state") {
            if (!InputService.initialize(deviceTree)) {
                UART.println("Input: ${InputService.status()}")
                return@register
            }
            InputService.poll()
            val mouse = InputService.mouseSnapshot()
            UART.println(
                "Mouse: x=${mouse.x} y=${mouse.y} dx=${mouse.deltaX} dy=${mouse.deltaY} " +
                        "buttons=${buttonText(mouse.left, mouse.right, mouse.middle)}"
            )
            var events = 0
            while (true) {
                val event = InputService.nextKeyboardEvent() ?: break
                UART.println("Key: code=${event.code} ${if (event.pressed) "down" else "up"}")
                events++
            }
            if (events == 0) UART.println("Key: no queued events")
        }
        registry.register("fs-info", "show FLX filesystem status") {
            val ok = FlxService.initialize(deviceTree)
            UART.println("FLX: ${FlxService.status()} (${if (ok) "online" else "offline"})")
        }
        registry.register("ls", "list a FLX directory") { args ->
            if (!FlxService.initialize(deviceTree)) {
                UART.println("FLX: ${FlxService.status()}")
                return@register
            }
            val path = args.getOrNull(0) ?: "/"
            val entries = FlxService.list(path)
            if (entries == null) {
                UART.println("FLX: path not found: $path")
                return@register
            }
            entries.forEach { entry ->
                val prefix = if (entry.kind == FlxEntryKind.Directory) "dir " else "file"
                UART.println("$prefix ${entry.size.toString().padStart(10)} ${entry.name}")
            }
        }
        registry.register("cat", "print a text preview from FLX") { args ->
            if (!FlxService.initialize(deviceTree)) {
                UART.println("FLX: ${FlxService.status()}")
                return@register
            }
            val path = args.getOrNull(0)
            if (path == null) {
                UART.println("usage: cat <path> [max-bytes]")
                return@register
            }
            val maxBytes = args.getOrNull(1)?.toUIntOrNull() ?: 512u
            val file = FlxService.open(path)
            if (file == null) {
                UART.println("FLX: file not found: $path")
                return@register
            }
            val bytes = file.readAll(maxBytes)
            if (bytes == null) {
                UART.println("FLX: read failed: $path")
                return@register
            }
            UART.println(asciiPreview(bytes))
        }
        registry.register("apps", "list runtime-loaded applications") {
            if (!FlxService.initialize(deviceTree)) {
                UART.println("FLX: ${FlxService.status()}")
                return@register
            }
            val entries = FlxService.list("/apps")
            if (entries == null) {
                UART.println("No /apps directory found.")
                return@register
            }
            entries.filter { it.kind == FlxEntryKind.File && it.name.endsWith(".app") }.forEach {
                UART.println(it.name.removeSuffix(".app"))
            }
        }
        registry.register("run", "load and run an application from FLX") { args ->
            val app = args.getOrNull(0)
            if (app == null) {
                UART.println("usage: run <app>")
                return@register
            }
            RuntimeAppLoader.run(deviceTree, app)
        }
    }

    private fun runGraphicsTest(deviceTree: DeviceTree?) {
        if (!GraphicsService.initialize(deviceTree)) {
            UART.println("Graphics: ${GraphicsService.status()}")
            return
        }

        val fb = GraphicsService.framebuffer()
        if (fb == null) {
            UART.println("Graphics: framebuffer unavailable")
            return
        }

        fb.clear(0x00101820u)
        fb.fillRect(24u, 24u, fb.mode.width / 3u, fb.mode.height / 3u, 0x00d94848u)
        fb.fillRect(fb.mode.width / 3u, fb.mode.height / 4u, fb.mode.width / 3u, fb.mode.height / 3u, 0x0048d96fu)
        fb.fillRect(fb.mode.width / 2u, fb.mode.height / 2u, fb.mode.width / 3u, fb.mode.height / 3u, 0x004875d9u)
        fb.present()
        UART.println("Graphics test pattern presented.")
    }

    private fun buttonText(left: Boolean, right: Boolean, middle: Boolean): String {
        val names = mutableListOf<String>()
        if (left) names.add("left")
        if (right) names.add("right")
        if (middle) names.add("middle")
        return if (names.isEmpty()) "none" else names.joinToString("+")
    }

}

private object RuntimeAppLoader {
    fun run(deviceTree: DeviceTree?, name: String) {
        if (!FlxService.initialize(deviceTree)) {
            UART.println("FLX: ${FlxService.status()}")
            return
        }

        val path = "/apps/$name.app"
        val file = FlxService.open(path)
        if (file == null) {
            UART.println("App not found: $name")
            return
        }

        val bytes = file.readAll(2048u)
        if (bytes == null) {
            UART.println("App load failed: $name")
            return
        }
        val manifest = parseManifest(asciiPreview(bytes))
        if (manifest["name"] != name || manifest["type"] != "kotlin") {
            UART.println("Invalid app manifest: $path")
            return
        }

        when (manifest["entry"]) {
            "snake" -> SnakeApplication.run(deviceTree)
            else -> UART.println("Unsupported app entry: ${manifest["entry"] ?: "<missing>"}")
        }
    }

    private fun parseManifest(text: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        val lines = text.split("\n", "\r\n", "\r")
        if (lines.firstOrNull()?.trim() != "KAPP1") return values
        lines.drop(1).forEach { line ->
            val trimmed = line.trim()
            val separator = trimmed.indexOf('=')
            if (separator > 0) {
                values[trimmed.substring(0, separator)] = trimmed.substring(separator + 1)
            }
        }
        return values
    }
}

private fun asciiPreview(bytes: ByteArray): String {
    val builder = StringBuilder()
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        builder.append(if (value in 32..126 || value == 10 || value == 13 || value == 9) value.toChar() else '.')
    }
    return builder.toString()
}
