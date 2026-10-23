package dev.motopoly

import dev.motopoly.command.MotopolyCommand
import dev.motopoly.selection.SelectionManager
import dev.motopoly.selection.WandListener
import dev.motopoly.world.UndoHistory
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.Executor

private val miniMessage = MiniMessage.miniMessage()

fun CommandSender.msg(text: String) = sendMessage(miniMessage.deserialize("<dark_aqua>[Motopoly]</dark_aqua> $text"))

class MotopolyPlugin : JavaPlugin() {
    lateinit var settings: Settings
        private set
    lateinit var history: UndoHistory
        private set
    val selections = SelectionManager()
    val async = Executor { Bukkit.getScheduler().runTaskAsynchronously(this, it) }

    override fun onEnable() {
        saveDefaultConfig()
        loadSettings()
        val wand = WandListener(this)
        server.pluginManager.registerEvents(wand, this)
        val command = MotopolyCommand(this, wand)
        getCommand("motopoly")!!.apply {
            setExecutor(command)
            tabCompleter = command
        }
    }

    fun loadSettings() {
        reloadConfig()
        settings = Settings.load(config)
        history = UndoHistory(settings.undoDepth)
    }
}
