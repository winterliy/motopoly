package dev.motopoly.selection

import dev.motopoly.MotopolyPlugin
import dev.motopoly.msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class WandListener(private val plugin: MotopolyPlugin) : Listener {
    private val wandKey = NamespacedKey(plugin, "wand")

    fun createWand(): ItemStack = ItemStack(Material.GOLDEN_SHOVEL).apply {
        editMeta { meta ->
            meta.displayName(Component.text("Motopoly 선택 도구", NamedTextColor.GOLD))
            meta.lore(
                listOf(
                    Component.text("좌클릭: 첫 번째 지점", NamedTextColor.GRAY),
                    Component.text("우클릭: 두 번째 지점", NamedTextColor.GRAY),
                )
            )
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
    }

    private fun isWand(item: ItemStack?) =
        item != null && item.hasItemMeta() && item.itemMeta.persistentDataContainer.has(wandKey)

    @EventHandler(ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !isWand(event.item)) return
        val block = event.clickedBlock ?: return
        val index = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> 1
            Action.RIGHT_CLICK_BLOCK -> 2
            else -> return
        }
        event.isCancelled = true
        val player = event.player
        if (!player.hasPermission("motopoly.use")) return
        setPoint(player, index, BlockPoint(block.x, block.y, block.z))
    }

    // Creative players break blocks instantly on left click; keep the wand harmless.
    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (isWand(event.player.inventory.itemInMainHand)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.selections.forget(event.player.uniqueId)
    }

    fun setPoint(player: Player, index: Int, point: BlockPoint) {
        val selection = plugin.selections.of(player.uniqueId)
        selection.set(index, player.world, point)
        val region = selection.region(player.world)
        val size = region?.let { " <gray>— ${it.width} x ${it.depth} (${it.columns}칸)" } ?: ""
        player.msg("<green>${index}번 지점: <white>$point$size")
    }
}
