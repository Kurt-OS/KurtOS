package fs

import drivers.storage.BlockStorageService
import fdt.DeviceTree

enum class FlxEntryKind {
    File,
    Directory,
}

object FlxService {
    private const val MAX_OBJECT_RECORDS: Int = 1024

    private var initialized = false
    private var mounted = false
    private var rootHash: ByteArray = ByteArray(32)
    private var objects: List<FlxObjectRecord> = emptyList()
    private var tableOffset: ULong = 0UL
    private var lastStatus = "not initialized"

    fun initialize(deviceTree: DeviceTree?): Boolean {
        if (initialized) return mounted
        initialized = true

        if (!BlockStorageService.initialize(deviceTree)) {
            lastStatus = BlockStorageService.status()
            return false
        }

        val superblock = BlockStorageService.readBytes(0UL, FLXConstants.FLX_BLOCK_SIZE)
        if (superblock == null) {
            lastStatus = "unable to read superblock"
            return false
        }

        val magic = ascii(superblock, 0, 4)
        val version = le32(superblock, 4)
        val blockSize = le32(superblock, 8)
        val objectCount = le32(superblock, 12)
        tableOffset = le64(superblock, 16)
        val tableSize = le64(superblock, 24)
        if (magic != FLXConstants.FLX_MAGIC || version != FLXConstants.FLX_VERSION || blockSize != FLXConstants.FLX_BLOCK_SIZE) {
            lastStatus = "invalid FLX superblock"
            return false
        }
        if (objectCount == 0u || tableSize != objectCount.toULong() * FLXConstants.FLX_OBJECT_RECORD_SIZE.toULong()) {
            lastStatus = "invalid FLX object table"
            return false
        }

        rootHash = slice(superblock, 32, 32)
        val table = BlockStorageService.readBytes(tableOffset, tableSize.toUInt())
        if (table == null) {
            lastStatus = "unable to read object table"
            return false
        }

        val parsed = mutableListOf<FlxObjectRecord>()
        var index = 0u
        while (index < objectCount) {
            val base = (index * FLXConstants.FLX_OBJECT_RECORD_SIZE).toInt()
            parsed.add(
                FlxObjectRecord(
                    hash = slice(table, base, 32),
                    type = le32(table, base + 32),
                    offset = le64(table, base + 40),
                    size = le64(table, base + 48),
                )
            )
            index++
        }
        objects = parsed

        if (findObject(rootHash, FLXConstants.FLX_OBJECT_TREE) == null) {
            lastStatus = "root tree missing"
            return false
        }

        mounted = true
        lastStatus = "mounted ${objects.size} objects"
        return true
    }

    fun status(): String = lastStatus

    fun list(path: String): List<FlxDirectoryEntry>? {
        val tree = resolveTree(path) ?: return null
        return readTree(tree).map {
            FlxDirectoryEntry(
                it.name,
                if (it.kind == FLXConstants.FLX_OBJECT_TREE) FlxEntryKind.Directory else FlxEntryKind.File,
                it.size
            )
        }
    }

    fun open(path: String): FlxFile? {
        val normalized = normalizePath(path)
        val parts = pathParts(normalized)
        if (parts.isEmpty()) return null

        var currentHash = rootHash
        for (i in parts.indices) {
            val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
            val entry = readTree(tree).firstOrNull { it.name == parts[i] } ?: return null
            if (i == parts.lastIndex) {
                val blob = findObject(entry.hash, FLXConstants.FLX_OBJECT_BLOB) ?: return null
                return FlxFile(blob)
            }
            if (entry.kind != FLXConstants.FLX_OBJECT_TREE) return null
            currentHash = entry.hash
        }
        return null
    }

    fun fileMagic(path: String): String? {
        val file = open(path) ?: return null
        val bytes = file.read(0UL, 4u) ?: return null
        if (bytes.size < 4) return null
        return ascii(bytes, 0, 4)
    }

    fun write(path: String, content: String): Boolean {
        val parts = pathParts(normalizePath(path))
        if (parts.isEmpty()) {
            lastStatus = "cannot write root directory"
            return false
        }

        val fileName = parts.last()
        val parentParts = parts.dropLast(1)
        val payload = content.encodeToByteArray()
        val blobHash = appendObject(FLXConstants.FLX_OBJECT_BLOB, payload) ?: return false
        val newRoot = rewriteTree(parentParts) { entries ->
            val existing = entries.firstOrNull { it.name == fileName }
            if (existing != null && existing.kind == FLXConstants.FLX_OBJECT_TREE) {
                lastStatus = "path is a directory: $path"
                null
            } else {
                entries
                    .filter { it.name != fileName }
                    .plus(FlxTreeEntry(fileName, FLXConstants.FLX_OBJECT_BLOB, payload.size.toULong(), blobHash))
                    .sortedBy { it.name }
            }
        } ?: return false

        rootHash = newRoot
        return persistSuperblock()
    }

    fun mkdir(path: String): Boolean {
        val parts = pathParts(normalizePath(path))
        if (parts.isEmpty()) return true

        val dirName = parts.last()
        val parentParts = parts.dropLast(1)
        val emptyTree = appendObject(FLXConstants.FLX_OBJECT_TREE, encodeTree(emptyList())) ?: return false
        val newRoot = rewriteTree(parentParts) { entries ->
            if (entries.any { it.name == dirName }) {
                lastStatus = "path already exists: $path"
                null
            } else {
                entries
                    .plus(FlxTreeEntry(dirName, FLXConstants.FLX_OBJECT_TREE, 0UL, emptyTree))
                    .sortedBy { it.name }
            }
        } ?: return false

        rootHash = newRoot
        return persistSuperblock()
    }

    private fun resolveTree(path: String): FlxObjectRecord? {
        return resolveTreeWithHash(pathParts(normalizePath(path)))?.second
    }

    private fun resolveTreeWithHash(parts: List<String>): Pair<ByteArray, FlxObjectRecord>? {
        var currentHash = rootHash
        if (parts.isEmpty()) {
            val root = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
            return currentHash to root
        }

        for (part in parts) {
            val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
            val entry = readTree(tree).firstOrNull { it.name == part } ?: return null
            if (entry.kind != FLXConstants.FLX_OBJECT_TREE) return null
            currentHash = entry.hash
        }
        val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
        return currentHash to tree
    }

    private fun readTree(record: FlxObjectRecord): List<FlxTreeEntry> {
        val payload = BlockStorageService.readBytes(record.offset, record.size.toUInt()) ?: return emptyList()
        val count = le32(payload, 0)
        val entries = mutableListOf<FlxTreeEntry>()
        var cursor = 4
        var index = 0u
        while (index < count && cursor + 48 <= payload.size) {
            val kind = le32(payload, cursor)
            val nameLength = le32(payload, cursor + 4).toInt()
            val size = le64(payload, cursor + 8)
            val hash = slice(payload, cursor + 16, 32)
            cursor += 48
            if (nameLength < 0 || cursor + nameLength > payload.size) break
            val name = ascii(payload, cursor, nameLength)
            cursor += nameLength
            entries.add(FlxTreeEntry(name, kind, size, hash))
            index++
        }
        return entries
    }

    private fun findObject(hash: ByteArray, type: UInt): FlxObjectRecord? =
        objects.firstOrNull { it.type == type && bytesEqual(it.hash, hash) }

    private fun rewriteTree(parts: List<String>, transform: (List<FlxTreeEntry>) -> List<FlxTreeEntry>?): ByteArray? =
        rewriteTree(rootHash, parts, transform)

    private fun rewriteTree(
        currentHash: ByteArray,
        parts: List<String>,
        transform: (List<FlxTreeEntry>) -> List<FlxTreeEntry>?,
    ): ByteArray? {
        val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
        val entries = readTree(tree)
        val updated = if (parts.isEmpty()) {
            transform(entries) ?: return null
        } else {
            val childName = parts.first()
            val child = entries.firstOrNull { it.name == childName && it.kind == FLXConstants.FLX_OBJECT_TREE }
            if (child == null) {
                lastStatus = "directory not found: $childName"
                return null
            }
            val childHash = rewriteTree(child.hash, parts.drop(1), transform) ?: return null
            entries
                .map { if (it.name == childName) it.copy(hash = childHash) else it }
                .sortedBy { it.name }
        }

        return appendObject(FLXConstants.FLX_OBJECT_TREE, encodeTree(updated))
    }

    private fun appendObject(type: UInt, payload: ByteArray): ByteArray? {
        if (objects.size >= MAX_OBJECT_RECORDS) {
            lastStatus = "FLX object table is full"
            return null
        }

        var salt = objects.size.toUInt()
        var hash = hashObject(type, payload, salt)
        while (findObject(hash, type) != null) {
            salt++
            hash = hashObject(type, payload, salt)
        }

        val offset = alignUp(objects.fold(0UL) { max, record -> maxOf(max, record.offset + record.size) }, FLXConstants.FLX_BLOCK_SIZE.toULong())
        val record = FlxObjectRecord(hash, type, offset, payload.size.toULong())
        if (!BlockStorageService.writeBytes(offset, payload)) {
            lastStatus = "unable to write FLX object payload"
            return null
        }
        if (!writeObjectRecord(objects.size, record)) {
            lastStatus = "unable to write FLX object record"
            return null
        }

        objects = objects + record
        return hash
    }

    private fun writeObjectRecord(index: Int, record: FlxObjectRecord): Boolean {
        val bytes = ByteArray(FLXConstants.FLX_OBJECT_RECORD_SIZE.toInt())
        record.hash.copyInto(bytes, 0)
        writeLe32(bytes, 32, record.type)
        writeLe64(bytes, 40, record.offset)
        writeLe64(bytes, 48, record.size)
        writeLe64(bytes, 56, record.size)
        return BlockStorageService.writeBytes(tableOffset + index.toULong() * FLXConstants.FLX_OBJECT_RECORD_SIZE.toULong(), bytes)
    }

    private fun persistSuperblock(): Boolean {
        val header = BlockStorageService.readBytes(0UL, FLXConstants.FLX_BLOCK_SIZE) ?: return false
        rootHash.copyInto(header, 32)
        writeLe32(header, 12, objects.size.toUInt())
        writeLe64(header, 24, objects.size.toULong() * FLXConstants.FLX_OBJECT_RECORD_SIZE.toULong())
        val end = alignUp(objects.fold(0UL) { max, record -> maxOf(max, record.offset + record.size) }, FLXConstants.FLX_BLOCK_SIZE.toULong())
        writeLe64(header, 64, end)
        val ok = BlockStorageService.writeBytes(0UL, header)
        lastStatus = if (ok) "mounted ${objects.size} objects" else "unable to write FLX superblock"
        return ok
    }
}

internal data class FlxObjectRecord(
    val hash: ByteArray,
    val type: UInt,
    val offset: ULong,
    val size: ULong,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FlxObjectRecord

        if (!hash.contentEquals(other.hash)) return false
        if (type != other.type) return false
        if (offset != other.offset) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
}

private data class FlxTreeEntry(
    val name: String,
    val kind: UInt,
    val size: ULong,
    val hash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FlxTreeEntry

        if (name != other.name) return false
        if (kind != other.kind) return false
        if (size != other.size) return false
        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

private fun encodeTree(entries: List<FlxTreeEntry>): ByteArray {
    var size = 4
    entries.forEach { size += 48 + it.name.length }
    val out = ByteArray(size)
    writeLe32(out, 0, entries.size.toUInt())
    var cursor = 4
    entries.forEach { entry ->
        val nameBytes = entry.name.encodeToByteArray()
        writeLe32(out, cursor, entry.kind)
        writeLe32(out, cursor + 4, nameBytes.size.toUInt())
        writeLe64(out, cursor + 8, entry.size)
        entry.hash.copyInto(out, cursor + 16)
        cursor += 48
        nameBytes.copyInto(out, cursor)
        cursor += nameBytes.size
    }
    return out
}

private fun normalizePath(path: String): String =
    path.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }

private fun pathParts(path: String): List<String> =
    path.split("/").filter { it.isNotEmpty() && it != "." }

private fun le32(bytes: ByteArray, offset: Int): UInt {
    val b0 = bytes[offset].toInt() and 0xff
    val b1 = bytes[offset + 1].toInt() and 0xff
    val b2 = bytes[offset + 2].toInt() and 0xff
    val b3 = bytes[offset + 3].toInt() and 0xff
    return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
}

private fun le64(bytes: ByteArray, offset: Int): ULong {
    var value = 0UL
    var i = 0
    while (i < 8) {
        value = value or ((bytes[offset + i].toInt() and 0xff).toULong() shl (i * 8))
        i++
    }
    return value
}

private fun slice(bytes: ByteArray, offset: Int, length: Int): ByteArray {
    val result = ByteArray(length)
    var i = 0
    while (i < length) {
        result[i] = bytes[offset + i]
        i++
    }
    return result
}

private fun writeLe32(target: ByteArray, offset: Int, value: UInt) {
    val raw = value.toLong()
    target[offset] = raw.toByte()
    target[offset + 1] = (raw shr 8).toByte()
    target[offset + 2] = (raw shr 16).toByte()
    target[offset + 3] = (raw shr 24).toByte()
}

private fun writeLe64(target: ByteArray, offset: Int, value: ULong) {
    var i = 0
    while (i < 8) {
        target[offset + i] = (value shr (i * 8)).toByte()
        i++
    }
}

private fun alignUp(value: ULong, alignment: ULong): ULong =
    (value + alignment - 1UL) and (alignment - 1UL).inv()

private fun hashObject(type: UInt, payload: ByteArray, salt: UInt): ByteArray {
    var a = 0xcbf29ce484222325UL xor type.toULong()
    var b = 0x100000001b3UL xor salt.toULong()
    for (byte in payload) {
        val v = (byte.toInt() and 0xff).toULong()
        a = (a xor v) * 0x100000001b3UL
        b = (b + v + (a shl 7)) xor (b shr 3)
    }
    val out = ByteArray(32)
    var x = a
    var y = b
    var i = 0
    while (i < 32) {
        x = (x xor (y shl 13)) * 0xff51afd7ed558ccdUL
        y = (y xor (x shr 11)) * 0xc4ceb9fe1a85ec53UL
        out[i] = (x xor y).toByte()
        i++
    }
    return out
}

private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
    val builder = StringBuilder()
    var i = 0
    while (i < length) {
        builder.append((bytes[offset + i].toInt() and 0xff).toChar())
        i++
    }
    return builder.toString()
}

private fun bytesEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}
