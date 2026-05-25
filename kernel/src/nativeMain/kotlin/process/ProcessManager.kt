package process

import hal.Clock
import hal.UART

enum class ProcessKind {
    Kernel,
    Userspace,
}

enum class ProcessState {
    Ready,
    Running,
    Exited,
    Failed,
}

data class ProcessHandle(val pid: Int)

private class ProcessNode(
    val pid: Int,
    val parentPid: Int?,
    val name: String,
    val kind: ProcessKind,
    val args: List<String>,
    val startedAtMs: Long,
) {
    val children = mutableListOf<Int>()
    var state: ProcessState = ProcessState.Ready
    var exitCode: Int? = null
    var endedAtMs: Long? = null
}

object ProcessManager {
    private const val ROOT_PID = 1

    private val processes = mutableListOf<ProcessNode>()
    private var nextPid = ROOT_PID + 1
    private var currentPid = ROOT_PID

    init {
        processes.add(
            ProcessNode(
                pid = ROOT_PID,
                parentPid = null,
                name = "kernel",
                kind = ProcessKind.Kernel,
                args = emptyList(),
                startedAtMs = 0L,
            ).also { it.state = ProcessState.Running }
        )
    }

    val kernel: ProcessHandle get() = ProcessHandle(ROOT_PID)
    val current: ProcessHandle get() = ProcessHandle(currentPid)

    fun spawn(parent: ProcessHandle, name: String, kind: ProcessKind, args: List<String>): ProcessHandle {
        val parentNode = find(parent.pid) ?: find(ROOT_PID)!!
        val node = ProcessNode(
            pid = nextPid++,
            parentPid = parentNode.pid,
            name = name,
            kind = kind,
            args = args,
            startedAtMs = Clock.uptimeMillis().toLong(),
        )
        processes.add(node)
        parentNode.children.add(node.pid)
        return ProcessHandle(node.pid)
    }

    fun enter(handle: ProcessHandle): ProcessHandle {
        val previous = ProcessHandle(currentPid)
        find(handle.pid)?.state = ProcessState.Running
        currentPid = handle.pid
        return previous
    }

    fun restore(handle: ProcessHandle) {
        currentPid = handle.pid
    }

    fun exit(handle: ProcessHandle, code: Int) {
        find(handle.pid)?.let {
            it.state = ProcessState.Exited
            it.exitCode = code
            it.endedAtMs = Clock.uptimeMillis().toLong()
        }
    }

    fun fail(handle: ProcessHandle, code: Int) {
        find(handle.pid)?.let {
            it.state = ProcessState.Failed
            it.exitCode = code
            it.endedAtMs = Clock.uptimeMillis().toLong()
        }
    }

    fun printTree() {
        printNode(ROOT_PID, 0)
    }

    private fun printNode(pid: Int, depth: Int) {
        val node = find(pid) ?: return
        val indent = "  ".repeat(depth)
        val code = node.exitCode?.let { " code=$it" } ?: ""
        val runtime = node.endedAtMs?.let { ended ->
            " ${ended - node.startedAtMs}ms"
        } ?: ""
        val argv = if (node.args.isEmpty()) "" else " ${node.args.joinToString(" ")}"
        UART.println("$indent${node.pid} ${node.state.name.lowercase()} ${node.kind.name.lowercase()} ${node.name}$argv$code$runtime")
        node.children.forEach { printNode(it, depth + 1) }
    }

    private fun find(pid: Int): ProcessNode? = processes.firstOrNull { it.pid == pid }
}
