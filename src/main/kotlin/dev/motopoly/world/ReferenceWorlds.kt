package dev.motopoly.world

import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.WorldCreator
import org.slf4j.Logger

/**
 * Hidden twin worlds with the same seed and generator as a real world. Their
 * chunks show what vanilla generated (or would generate) at any coordinate,
 * untouched by players, so new ground can borrow real vanilla topography,
 * surface materials and plants.
 */
class ReferenceWorlds(private val logger: Logger) {
    companion object {
        const val PREFIX = "motopoly_ref_"
    }

    fun isReference(world: World) = world.name.startsWith(PREFIX)

    /**
     * The twin of [world], created on first use (a few seconds, once per
     * server run at most). Main thread only. Null if it can't be created.
     */
    fun of(world: World): World? {
        if (isReference(world)) return null
        val name = PREFIX + world.name
        Bukkit.getWorld(name)?.let { return it }
        return try {
            WorldCreator(name).copy(world).keepSpawnLoaded(TriState.FALSE).createWorld()?.also { ref ->
                // Nobody plays here: keep it from ticking anything it doesn't need to.
                ref.difficulty = Difficulty.PEACEFUL
                ref.setGameRule(GameRule.DO_MOB_SPAWNING, false)
                ref.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
                ref.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
                logger.info("Created reference world {} for {}", name, world.name)
            }
        } catch (e: Exception) {
            logger.warn("Could not create reference world {}; falling back to noise", name, e)
            null
        }
    }
}
