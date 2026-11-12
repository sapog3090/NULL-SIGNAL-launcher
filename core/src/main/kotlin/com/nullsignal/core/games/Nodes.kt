package com.nullsignal.core.games

import kotlin.random.Random

/**
 * Головоломка «Узлы»: повороты плиток, чтобы соединить всю сеть с центральным сервером.
 * Плитка — битовая маска выходов: 1 верх, 2 право, 4 низ, 8 лево.
 */
class Nodes(val size: Int = 5, random: Random = Random.Default) {
    val source = (size / 2) * size + size / 2
    val tiles = IntArray(size * size)

    init {
        require(size >= 2)
        val solution = generate(random)
        do {
            for (i in tiles.indices) tiles[i] = rotate(solution[i], random.nextInt(4))
        } while (solved())
    }

    private fun generate(random: Random): IntArray {
        val sol = IntArray(size * size)
        val visited = BooleanArray(size * size)
        val stack = ArrayDeque<Int>()
        stack.addLast(source); visited[source] = true
        while (stack.isNotEmpty()) {
            val cur = stack.last()
            val options = DIRS.filter { d -> neighbor(cur, d)?.let { !visited[it] } == true }
            if (options.isEmpty()) { stack.removeLast(); continue }
            val d = options[random.nextInt(options.size)]
            val n = neighbor(cur, d)!!
            sol[cur] = sol[cur] or d
            sol[n] = sol[n] or opposite(d)
            visited[n] = true
            stack.addLast(n)
        }
        return sol
    }

    fun neighbor(i: Int, d: Int): Int? {
        val r = i / size; val c = i % size
        return when (d) {
            UP -> if (r > 0) i - size else null
            RIGHT -> if (c < size - 1) i + 1 else null
            DOWN -> if (r < size - 1) i + size else null
            LEFT -> if (c > 0) i - 1 else null
            else -> null
        }
    }

    fun rotate(i: Int) { tiles[i] = rotate(tiles[i], 1) }

    /** Плитки, соединённые с сервером. */
    fun connected(): Set<Int> {
        val seen = mutableSetOf(source)
        val queue = ArrayDeque(listOf(source))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (d in DIRS) {
                if (tiles[cur] and d == 0) continue
                val n = neighbor(cur, d) ?: continue
                if (tiles[n] and opposite(d) != 0 && seen.add(n)) queue.addLast(n)
            }
        }
        return seen
    }

    fun solved(): Boolean {
        for (i in tiles.indices) for (d in DIRS) {
            if (tiles[i] and d == 0) continue
            val n = neighbor(i, d) ?: return false
            if (tiles[n] and opposite(d) == 0) return false
        }
        return connected().size == tiles.size
    }

    companion object {
        const val UP = 1; const val RIGHT = 2; const val DOWN = 4; const val LEFT = 8
        val DIRS = listOf(UP, RIGHT, DOWN, LEFT)
        fun opposite(d: Int) = when (d) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; else -> LEFT }
        /** Поворот по часовой на 90° × times. */
        fun rotate(m: Int, times: Int): Int {
            var x = m
            repeat(times) { x = ((x shl 1) or (x shr 3)) and 15 }
            return x
        }
    }
}
