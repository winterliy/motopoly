package dev.motopoly.world

import java.util.ArrayDeque
import java.util.UUID

class UndoHistory(private val depth: Int) {
    private val stacks = HashMap<UUID, ArrayDeque<ChangeSet>>()

    fun push(playerId: UUID, changes: ChangeSet) {
        if (depth == 0) return
        val stack = stacks.getOrPut(playerId) { ArrayDeque() }
        stack.push(changes)
        while (stack.size > depth) stack.removeLast()
    }

    fun pop(playerId: UUID): ChangeSet? = stacks[playerId]?.poll()
}
