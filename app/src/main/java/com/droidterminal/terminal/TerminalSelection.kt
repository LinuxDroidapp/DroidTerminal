package com.droidterminal.terminal

data class TerminalPoint(val col: Int, val row: Int)

class TerminalSelection {
    var isActive: Boolean = false
    var startCol: Int = 0
    var startRow: Int = 0
    var endCol: Int = 0
    var endRow: Int = 0

    fun start(col: Int, row: Int) {
        startCol = col
        startRow = row
        endCol = col
        endRow = row
        isActive = true
    }

    fun update(col: Int, row: Int) {
        endCol = col
        endRow = row
    }

    fun clear() {
        isActive = false
    }

    fun isSelected(col: Int, row: Int): Boolean {
        if (!isActive) return false

        val (normStartRow, normStartCol, normEndRow, normEndCol) = getNormalizedBounds()

        if (row < normStartRow || row > normEndRow) return false
        if (row == normStartRow && row == normEndRow) {
            return col in normStartCol..normEndCol
        }
        if (row == normStartRow) {
            return col >= normStartCol
        }
        if (row == normEndRow) {
            return col <= normEndCol
        }
        return true
    }

    fun getNormalizedBounds(): IntArray {
        return if (startRow < endRow || (startRow == endRow && startCol <= endCol)) {
            intArrayOf(startRow, startCol, endRow, endCol)
        } else {
            intArrayOf(endRow, endCol, startRow, startCol)
        }
    }

    fun extractSelectedText(buffer: TerminalBuffer): String {
        if (!isActive) return ""
        val (sRow, sCol, eRow, eCol) = getNormalizedBounds()
        val sb = StringBuilder()

        for (r in sRow..eRow) {
            val fromCol = if (r == sRow) sCol else 0
            val toCol = if (r == eRow) eCol else buffer.cols - 1
            for (c in fromCol..toCol) {
                val cell = buffer.getCell(c, r)
                if (cell != null && cell.codePoint != 0) {
                    sb.append(Character.toChars(cell.codePoint))
                } else {
                    sb.append(' ')
                }
            }
            if (r < eRow) sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
