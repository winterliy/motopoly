package dev.motopoly.selection

import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

data class BlockPoint(val x: Int, val y: Int, val z: Int) {
    override fun toString() = "($x, $y, $z)"

    companion object {
        fun of(loc: Location) = BlockPoint(loc.blockX, loc.blockY, loc.blockZ)
    }
}

/** Column rectangle on the XZ plane, both ends inclusive. */
data class Region(val world: World, val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
    val width get() = maxX - minX + 1
    val depth get() = maxZ - minZ + 1
    val columns get() = width.toLong() * depth
}

class Selection {
    var worldId: UUID? = null
        private set
    var pos1: BlockPoint? = null
        private set
    var pos2: BlockPoint? = null
        private set

    fun set(index: Int, world: World, point: BlockPoint) {
        // Switching worlds drops the other corner, it can't make sense any more.
        if (worldId != world.uid) {
            pos1 = null
            pos2 = null
            worldId = world.uid
        }
        if (index == 1) pos1 = point else pos2 = point
    }

    fun region(world: World): Region? {
        val a = pos1 ?: return null
        val b = pos2 ?: return null
        if (worldId != world.uid) return null
        return Region(world, minOf(a.x, b.x), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.z, b.z))
    }
}

class SelectionManager {
    private val selections = HashMap<UUID, Selection>()

    fun of(playerId: UUID) = selections.getOrPut(playerId) { Selection() }

    fun forget(playerId: UUID) {
        selections.remove(playerId)
    }
}
