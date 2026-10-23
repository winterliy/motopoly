package dev.motopoly.world

import org.bukkit.block.data.BlockData
import java.util.UUID

/**
 * Compact list of block edits: packed positions plus palette indices for the
 * before/after states. Large flattens touch millions of blocks, so no per-block objects.
 */
class ChangeSet(val worldId: UUID) {
    private val palette = ArrayList<BlockData>()
    private val paletteIndex = HashMap<BlockData, Int>()

    private var positions = LongArray(1024)
    private var before = IntArray(1024)
    private var after = IntArray(1024)
    var size = 0
        private set

    fun add(x: Int, y: Int, z: Int, old: BlockData, new: BlockData) {
        if (old == new) return
        if (size == positions.size) {
            val cap = size * 2
            positions = positions.copyOf(cap)
            before = before.copyOf(cap)
            after = after.copyOf(cap)
        }
        positions[size] = pack(x, y, z)
        before[size] = idOf(old)
        after[size] = idOf(new)
        size++
    }

    fun forEach(range: IntRange, undo: Boolean, action: (x: Int, y: Int, z: Int, data: BlockData) -> Unit) {
        val states = if (undo) before else after
        for (i in range) {
            val p = positions[i]
            action(unpackX(p), unpackY(p), unpackZ(p), palette[states[i]])
        }
    }

    private fun idOf(data: BlockData) = paletteIndex.getOrPut(data) { palette.add(data); palette.size - 1 }

    // Same layout as vanilla BlockPos.asLong: 26 bits X, 26 bits Z, 12 bits Y.
    private fun pack(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

    private fun unpackX(p: Long) = (p shr 38).toInt()
    private fun unpackY(p: Long) = (p shl 52 shr 52).toInt()
    private fun unpackZ(p: Long) = (p shl 26 shr 38).toInt()
}
