package userspace

import drivers.input.InputService
import fdt.DeviceTree
import fs.FlxEntryKind
import fs.FlxService
import graphics.GraphicsService
import hal.Clock
import hal.Memory
import hal.UART
import process.ProcessHandle
import process.ProcessKind
import process.ProcessManager
import shell.CommandRegistry

object UserspaceRuntime {
    private val standardApps = listOf(
        "shell", "cat", "clear", "cp", "echo", "edit", "ls", "mem",
        "mkdir", "mkf", "mv", "pwd", "reboot", "rm", "shutdown", "snake",
    )

    fun install(registry: CommandRegistry, deviceTree: DeviceTree?) {
        registry.register("help", "show this message") {
            registry.printHelp()
        }
        registry.register("apps", "list FLX userspace applications") {
            listApps(deviceTree)
        }
        registry.register("ps", "show process tree") {
            ProcessManager.printTree()
        }
        registry.register("run", "run a userspace application") { args ->
            val name = args.firstOrNull()
            if (name == null) {
                UART.println("usage: run <app> [args...]")
            } else {
                run(deviceTree, name, args.drop(1))
            }
        }
        standardApps.forEach { app ->
            registry.register(app, "userspace application") { args ->
                run(deviceTree, app, args)
            }
        }
    }

    fun run(deviceTree: DeviceTree?, name: String, args: List<String>): Int =
        run(deviceTree, name, args, ProcessManager.current)

    fun run(deviceTree: DeviceTree?, name: String, args: List<String>, parent: ProcessHandle): Int {
        val appName = name.removeSuffix(".app")
        val process = ProcessManager.spawn(parent, appName, ProcessKind.Userspace, args)
        val previous = ProcessManager.enter(process)
        if (!FlxService.initialize(deviceTree)) {
            UART.println("FLX: ${FlxService.status()}")
            ProcessManager.fail(process, 1)
            ProcessManager.restore(previous)
            return 1
        }

        val path = "/bin/$appName.app"
        val file = FlxService.open(path)
        if (file == null) {
            UART.println("App not found: $appName")
            ProcessManager.fail(process, 127)
            ProcessManager.restore(previous)
            return 127
        }
        val bytes = file.readAll(256u * 1024u)
        if (bytes == null) {
            UART.println("App load failed: $appName")
            ProcessManager.fail(process, 1)
            ProcessManager.restore(previous)
            return 1
        }
        val text = ascii(bytes)
        val program = KappProgram.parse(text)
        if (program == null) {
            UART.println("Invalid app bytecode: $path")
            ProcessManager.fail(process, 1)
            ProcessManager.restore(previous)
            return 1
        }
        val code = KappMachine(program, Host(deviceTree, args, process)).callMain()
        ProcessManager.exit(process, code)
        ProcessManager.restore(previous)
        return code
    }

    private fun listApps(deviceTree: DeviceTree?) {
        if (!FlxService.initialize(deviceTree)) {
            UART.println("FLX: ${FlxService.status()}")
            return
        }
        val entries = FlxService.list("/bin")
        if (entries == null) {
            UART.println("No /bin directory found.")
            return
        }
        entries.filter { it.kind == FlxEntryKind.File && it.name.endsWith(".app") }
            .forEach { UART.println(it.name.removeSuffix(".app")) }
    }
}

private data class KappProgram(val functions: Map<String, KappFunction>) {
    companion object {
        fun parse(text: String): KappProgram? {
            val rawLines = text.split("\n", "\r\n", "\r")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (rawLines.firstOrNull() != "KAPP2") return null

            val functions = mutableMapOf<String, KappFunction>()
            var index = 1
            while (index < rawLines.size) {
                val header = rawLines[index].split(" ").filter { it.isNotEmpty() }
                if (header.firstOrNull() != "func" || header.size < 2) return null
                val name = header[1]
                val params = header.drop(2)
                index++
                val lines = mutableListOf<List<String>>()
                while (index < rawLines.size && rawLines[index] != "end") {
                    lines.add(rawLines[index].split(" ").filter { it.isNotEmpty() })
                    index++
                }
                if (index >= rawLines.size) return null
                functions[name] = KappFunction.compile(name, params, lines) ?: return null
                index++
            }
            return KappProgram(functions)
        }
    }
}

private class KappFunction private constructor(
    val name: String,
    val paramSlots: IntArray,
    val localCount: Int,
    val instructions: Array<KappInstruction>,
) {
    val frame = Frame(localCount)

    companion object {
        fun compile(name: String, params: List<String>, lines: List<List<String>>): KappFunction? {
            val slots = mutableMapOf<String, Int>()
            fun slot(name: String): Int = slots.getOrPut(name) { slots.size }

            val paramSlots = IntArray(params.size)
            params.forEachIndexed { index, param -> paramSlots[index] = slot(param) }

            val labels = mutableMapOf<String, Int>()
            var emitted = 0
            lines.forEach { parts ->
                if (parts.getOrNull(0) == "label") {
                    parts.getOrNull(1)?.let { labels[it] = emitted }
                } else {
                    emitted++
                }
            }

            val instructions = mutableListOf<KappInstruction>()
            for (parts in lines) {
                when (parts.getOrNull(0)) {
                    "label" -> Unit
                    "lit" -> instructions.add(KappInstruction.Lit(slot(parts[1]), parts[2].toLong()))
                    "str" -> instructions.add(KappInstruction.Str(slot(parts[1]), decodeHex(parts.getOrNull(2) ?: "")))
                    "copy" -> instructions.add(KappInstruction.Copy(slot(parts[1]), slot(parts[2])))
                    "un" -> instructions.add(KappInstruction.Un(slot(parts[1]), parts[2], slot(parts[3])))
                    "bin" -> instructions.add(KappInstruction.Bin(slot(parts[1]), parts[2], slot(parts[3]), slot(parts[4])))
                    "call" -> {
                        val count = parts[3].toInt()
                        val args = IntArray(count)
                        var i = 0
                        while (i < count) {
                            args[i] = slot(parts[4 + i])
                            i++
                        }
                        instructions.add(KappInstruction.Call(slot(parts[1]), parts[2], args))
                    }
                    "jmp" -> instructions.add(KappInstruction.Jmp(labels[parts[1]] ?: return null))
                    "jnz" -> instructions.add(
                        KappInstruction.Jnz(
                            slot(parts[1]),
                            labels[parts[2]] ?: return null,
                            labels[parts[3]] ?: return null,
                        )
                    )
                    "ret" -> instructions.add(KappInstruction.Ret(parts.getOrNull(1)?.let { slot(it) } ?: -1))
                    else -> return null
                }
            }
            return KappFunction(name, paramSlots, slots.size, instructions.toTypedArray())
        }
    }
}

private sealed class KappInstruction {
    class Lit(val dest: Int, val value: Long) : KappInstruction()
    class Str(val dest: Int, val value: String) : KappInstruction()
    class Copy(val dest: Int, val src: Int) : KappInstruction()
    class Un(val dest: Int, val op: String, val src: Int) : KappInstruction()
    class Bin(val dest: Int, val op: String, val left: Int, val right: Int) : KappInstruction()
    class Call(val dest: Int, val target: String, val args: IntArray) : KappInstruction()
    class Jmp(val target: Int) : KappInstruction()
    class Jnz(val cond: Int, val trueTarget: Int, val falseTarget: Int) : KappInstruction()
    class Ret(val src: Int) : KappInstruction()
}

private class Frame(size: Int) {
    private val ints = LongArray(size)
    private val strings = arrayOfNulls<String>(size)
    private val isString = BooleanArray(size)

    fun clear() {
        var i = 0
        while (i < isString.size) {
            if (isString[i]) strings[i] = null
            isString[i] = false
            ints[i] = 0L
            i++
        }
    }

    fun setInt(slot: Int, value: Long) {
        if (isString[slot]) strings[slot] = null
        isString[slot] = false
        ints[slot] = value
    }

    fun setString(slot: Int, value: String) {
        strings[slot] = value
        isString[slot] = true
        ints[slot] = 0L
    }

    fun copy(dest: Int, source: Frame, src: Int) {
        if (source.isString[src]) {
            setString(dest, source.str(src))
        } else {
            setInt(dest, source.int(src))
        }
    }

    fun int(slot: Int): Long =
        if (isString[slot]) strings[slot]?.toLongOrNull() ?: 0L else ints[slot]

    fun str(slot: Int): String =
        if (isString[slot]) strings[slot] ?: "" else ints[slot].toString()

    fun hasString(slot: Int): Boolean = isString[slot]

    fun isTruthy(slot: Int): Boolean = int(slot) != 0L
}

private class KappMachine(
    private val program: KappProgram,
    private val host: Host,
) {
    private var returnIsString = false
    private var returnInt = 0L
    private var returnString = ""

    init {
        host.currentMachine = this
    }

    fun callMain(): Int {
        call("main", null, EMPTY_ARGS)
        return returnInt.toInt()
    }

    private fun call(name: String, caller: Frame?, args: IntArray) {
        val fn = program.functions[name]
        if (fn == null) {
            host.call(name, caller, args)
            return
        }

        val frame = fn.frame
        frame.clear()
        var param = 0
        while (param < fn.paramSlots.size) {
            if (caller == null || param >= args.size) {
                frame.setInt(fn.paramSlots[param], 0L)
            } else {
                frame.copy(fn.paramSlots[param], caller, args[param])
            }
            param++
        }

        var pc = 0
        while (pc < fn.instructions.size) {
            when (val instruction = fn.instructions[pc]) {
                is KappInstruction.Lit -> frame.setInt(instruction.dest, instruction.value)
                is KappInstruction.Str -> frame.setString(instruction.dest, instruction.value)
                is KappInstruction.Copy -> frame.copy(instruction.dest, frame, instruction.src)
                is KappInstruction.Un -> frame.setInt(instruction.dest, unary(instruction.op, frame.int(instruction.src)))
                is KappInstruction.Bin -> frame.setInt(instruction.dest, binary(instruction.op, frame.int(instruction.left), frame.int(instruction.right)))
                is KappInstruction.Call -> {
                    call(instruction.target, frame, instruction.args)
                    if (returnIsString) frame.setString(instruction.dest, returnString) else frame.setInt(instruction.dest, returnInt)
                }
                is KappInstruction.Jmp -> {
                    pc = instruction.target
                    continue
                }
                is KappInstruction.Jnz -> {
                    pc = if (frame.isTruthy(instruction.cond)) instruction.trueTarget else instruction.falseTarget
                    continue
                }
                is KappInstruction.Ret -> {
                    if (instruction.src < 0) {
                        setReturnInt(0L)
                    } else {
                        setReturnFrom(frame, instruction.src)
                    }
                    return
                }
            }
            pc++
        }
        setReturnInt(0L)
    }

    fun setReturnInt(value: Long) {
        returnIsString = false
        returnInt = value
        returnString = ""
    }

    fun setReturnString(value: String) {
        returnIsString = true
        returnInt = 0L
        returnString = value
    }

    private fun setReturnFrom(frame: Frame, slot: Int) {
        if (frame.hasString(slot)) setReturnString(frame.str(slot)) else setReturnInt(frame.int(slot))
    }

    private fun unary(op: String, value: Long): Long = when (op) {
        "sub" -> -value
        "not" -> if (value == 0L) 1L else 0L
        "inv" -> value.inv()
        else -> 0L
    }

    private fun binary(op: String, a: Long, b: Long): Long = when (op) {
        "add" -> a + b
        "sub" -> a - b
        "mul" -> a * b
        "div" -> if (b == 0L) 0L else a / b
        "rem" -> if (b == 0L) 0L else a % b
        "eq" -> if (a == b) 1L else 0L
        "ne" -> if (a != b) 1L else 0L
        "lt" -> if (a < b) 1L else 0L
        "le" -> if (a <= b) 1L else 0L
        "gt" -> if (a > b) 1L else 0L
        "ge" -> if (a >= b) 1L else 0L
        "and" -> a and b
        "or" -> a or b
        "xor" -> a xor b
        "shl" -> a shl b.toInt()
        "shr" -> a shr b.toInt()
        else -> 0L
    }

    companion object {
        private val EMPTY_ARGS = IntArray(0)
    }
}

private class Host(
    private val deviceTree: DeviceTree?,
    private val args: List<String>,
    private val process: ProcessHandle,
) {
    private val arrays = mutableListOf<IntArray>()

    fun call(name: String, frame: Frame?, args: IntArray) {
        when (name) {
            "print" -> {
                UART.print(stringArg(frame, args, 0))
                setInt(0)
            }
            "println" -> {
                UART.println(stringArg(frame, args, 0))
                setInt(0)
            }
            "putc" -> {
                UART.putChar(intArg(frame, args, 0).toInt().toChar())
                setInt(0)
            }
            "read_line" -> setString(UART.readLine())
            "try_read_char" -> setInt(UART.tryReadChar()?.code?.toLong() ?: -1L)
            "argc" -> setInt(this.args.size.toLong())
            "arg" -> setString(this.args.getOrNull(intArg(frame, args, 0).toInt()) ?: "")
            "exec" -> {
                val app = stringArg(frame, args, 0)
                setInt(UserspaceRuntime.run(deviceTree, app, emptyList(), process).toLong())
            }
            "mem_info" -> setString("Heap: base=0x${Memory.heapStart.toString(16)} size=${Memory.heapSize} bytes\n")
            "fs_read" -> setString(readFile(stringArg(frame, args, 0)))
            "fs_list" -> setString(listDir(stringArg(frame, args, 0).ifBlank { "/" }))
            "fs_write" -> setInt(if (FlxService.write(stringArg(frame, args, 0), stringArg(frame, args, 1))) 0 else 1)
            "fs_mkdir" -> setInt(if (FlxService.mkdir(stringArg(frame, args, 0))) 0 else 1)
            "fs_delete", "fs_copy", "fs_move" -> {
                UART.println("FLX operation is not implemented yet.")
                setInt(1)
            }
            "clock_ms" -> setInt(Clock.uptimeMillis().toLong())
            "array_new" -> arrayNew(frame, args)
            "array_get" -> arrayGet(frame, args)
            "array_set" -> arraySet(frame, args)
            "gfx_init" -> setInt(if (GraphicsService.initialize(deviceTree)) 1 else 0)
            "gfx_width" -> setInt(GraphicsService.framebuffer()?.mode?.width?.toLong() ?: 0L)
            "gfx_height" -> setInt(GraphicsService.framebuffer()?.mode?.height?.toLong() ?: 0L)
            "gfx_clear" -> {
                GraphicsService.framebuffer()?.clear(intArg(frame, args, 0).toUInt())
                setInt(0)
            }
            "gfx_fill_rect" -> {
                GraphicsService.framebuffer()?.fillRect(
                    intArg(frame, args, 0).toUInt(),
                    intArg(frame, args, 1).toUInt(),
                    intArg(frame, args, 2).toUInt(),
                    intArg(frame, args, 3).toUInt(),
                    intArg(frame, args, 4).toUInt(),
                )
                setInt(0)
            }
            "gfx_present" -> setInt(if (GraphicsService.framebuffer()?.presentAll() == true) 1 else 0)
            "input_init" -> setInt(if (InputService.initialize(deviceTree)) 1 else 0)
            "input_poll" -> {
                InputService.poll()
                setInt(0)
            }
            "key_down" -> setInt(if (InputService.isKeyDown(intArg(frame, args, 0).toUShort())) 1 else 0)
            else -> {
                UART.println("Unknown userspace host call: $name")
                setInt(1)
            }
        }
    }

    private fun readFile(path: String): String {
        val file = FlxService.open(path) ?: return ""
        val bytes = file.readAll(64u * 1024u) ?: return ""
        return ascii(bytes)
    }

    private fun listDir(path: String): String {
        val entries = FlxService.list(path) ?: return ""
        val builder = StringBuilder()
        entries.forEach { entry ->
            builder.append(if (entry.kind == FlxEntryKind.Directory) "dir  " else "file ")
            builder.append(entry.size.toString().padStart(10))
            builder.append(' ')
            builder.append(entry.name)
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun arrayNew(frame: Frame?, args: IntArray) {
        val size = intArg(frame, args, 0).coerceIn(0L, 4096L).toInt()
        val initial = intArg(frame, args, 1).toInt()
        arrays.add(IntArray(size) { initial })
        setInt(arrays.size.toLong())
    }

    private fun arrayGet(frame: Frame?, args: IntArray) {
        val array = arrays.getOrNull(intArg(frame, args, 0).toInt() - 1)
        if (array == null) {
            setInt(0)
            return
        }
        val index = intArg(frame, args, 1).toInt()
        setInt(array.getOrNull(index)?.toLong() ?: 0L)
    }

    private fun arraySet(frame: Frame?, args: IntArray) {
        val array = arrays.getOrNull(intArg(frame, args, 0).toInt() - 1)
        if (array == null) {
            setInt(1)
            return
        }
        val index = intArg(frame, args, 1).toInt()
        if (index !in array.indices) {
            setInt(1)
            return
        }
        array[index] = intArg(frame, args, 2).toInt()
        setInt(0)
    }

    private fun intArg(frame: Frame?, args: IntArray, index: Int): Long =
        if (frame == null || index !in args.indices) 0L else frame.int(args[index])

    private fun stringArg(frame: Frame?, args: IntArray, index: Int): String =
        if (frame == null || index !in args.indices) "" else frame.str(args[index])

    private fun setInt(value: Long) {
        currentMachine?.setReturnInt(value)
    }

    private fun setString(value: String) {
        currentMachine?.setReturnString(value)
    }

    var currentMachine: KappMachine? = null
}

private fun ascii(bytes: ByteArray): String {
    val builder = StringBuilder()
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        builder.append(if (value in 32..126 || value == 10 || value == 13 || value == 9) value.toChar() else '.')
    }
    return builder.toString()
}

private fun decodeHex(hex: String): String {
    val bytes = ByteArray(hex.length / 2)
    var i = 0
    while (i < bytes.size) {
        val hi = hexDigit(hex[i * 2])
        val lo = hexDigit(hex[i * 2 + 1])
        bytes[i] = ((hi shl 4) or lo).toByte()
        i++
    }
    return bytes.decodeToString()
}

private fun hexDigit(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> 0
    }
