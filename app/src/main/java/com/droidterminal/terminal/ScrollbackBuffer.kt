package com.droidterminal.terminal

import java.util.ArrayDeque

/**
 * Ring buffer for off-screen terminal history.
 * Bounded by maxCapacity to prevent unbounded memory growth.
 */
class ScrollbackBuffer(
    var maxCapacity: Int = 10000
) {
    private val lines = ArrayDeque<Array<TerminalCell>>()

    @Synchronized
    fun pushLine(line: Array<TerminalCell>) {
        if (lines.size >= maxCapacity) {
            lines.removeFirst()
        }
        val copy = Array(line.size) { i ->
            TerminalCell().apply { copyFrom(line[i]) }
        }
        lines.addLast(copy)
    }

    @Synchronized
    fun getLine(index: Int): Array<TerminalCell>? {
        if (index < 0 || index >= lines.size) return null
        // ArrayDeque indexed access: convert to list view or iterate
        var i = 0
        for (line in lines) {
            if (i == index) return line
            i++
        }
        return null
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun getAllAsText(): String {
        val sb = StringBuilder()
        for (line in lines) {
            for (cell in line) {
                if (cell.codePoint != 0) {
                    sb.append(Character.toChars(cell.codePoint))
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
