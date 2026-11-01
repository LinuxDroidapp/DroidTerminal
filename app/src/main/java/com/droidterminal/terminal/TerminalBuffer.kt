package com.droidterminal.terminal

class TerminalBuffer(
    var cols: Int = 80,
    var rows: Int = 24
) {
    var primaryScreen: Array<Array<TerminalCell>> = createGrid(cols, rows)
    var alternateScreen: Array<Array<TerminalCell>> = createGrid(cols, rows)
    var isAlternateScreenActive: Boolean = false

    val scrollback = ScrollbackBuffer()
    val cursor = CursorState()
    val savedAlternateCursor = CursorState()

    var topMargin: Int = 0
    var bottomMargin: Int = rows - 1
    var autowrap: Boolean = true
    var originMode: Boolean = false
    var applicationCursorMode: Boolean = false
    var bracketedPasteMode: Boolean = false

    companion object {
        private fun createGrid(cols: Int, rows: Int): Array<Array<TerminalCell>> {
            return Array(rows) {
                Array(cols) { TerminalCell() }
            }
        }
    }

    val activeScreen: Array<Array<TerminalCell>>
        get() = if (isAlternateScreenActive) alternateScreen else primaryScreen

    fun getCell(col: Int, row: Int): TerminalCell? {
        if (row in 0 until rows && col in 0 until cols) {
            return activeScreen[row][col]
        }
        return null
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return

        val newPrimary = createGrid(newCols, newRows)
        val newAlternate = createGrid(newCols, newRows)

        val minR = minOf(rows, newRows)
        val minC = minOf(cols, newCols)

        for (r in 0 until minR) {
            for (c in 0 until minC) {
                newPrimary[r][c].copyFrom(primaryScreen[r][c])
                newAlternate[r][c].copyFrom(alternateScreen[r][c])
            }
        }

        primaryScreen = newPrimary
        alternateScreen = newAlternate
        cols = newCols
        rows = newRows

        topMargin = 0
        bottomMargin = rows - 1
        cursor.col = cursor.col.coerceIn(0, cols - 1)
        cursor.row = cursor.row.coerceIn(0, rows - 1)
    }

    fun switchAlternateScreen(enable: Boolean) {
        if (isAlternateScreenActive != enable) {
            isAlternateScreenActive = enable
            if (enable) {
                savedAlternateCursor.save()
                clearScreen(2)
            } else {
                cursor.col = savedAlternateCursor.savedCol
                cursor.row = savedAlternateCursor.savedRow
            }
        }
    }

    fun setMargins(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(0, rows - 1)
        if (t < b) {
            topMargin = t
            bottomMargin = b
            cursor.col = 0
            cursor.row = if (originMode) topMargin else 0
        }
    }

    fun scrollUp(count: Int = 1) {
        val screen = activeScreen
        for (i in 0 until count) {
            if (!isAlternateScreenActive && topMargin == 0) {
                scrollback.pushLine(screen[topMargin])
            }
            for (r in topMargin until bottomMargin) {
                for (c in 0 until cols) {
                    screen[r][c].copyFrom(screen[r + 1][c])
                }
            }
            for (c in 0 until cols) {
                screen[bottomMargin][c].reset()
            }
        }
    }

    fun scrollDown(count: Int = 1) {
        val screen = activeScreen
        for (i in 0 until count) {
            for (r in bottomMargin downTo topMargin + 1) {
                for (c in 0 until cols) {
                    screen[r][c].copyFrom(screen[r - 1][c])
                }
            }
            for (c in 0 until cols) {
                screen[topMargin][c].reset()
            }
        }
    }

    fun insertLines(count: Int) {
        if (cursor.row in topMargin..bottomMargin) {
            val oldTop = topMargin
            topMargin = cursor.row
            scrollDown(count)
            topMargin = oldTop
        }
    }

    fun deleteLines(count: Int) {
        if (cursor.row in topMargin..bottomMargin) {
            val oldTop = topMargin
            topMargin = cursor.row
            scrollUp(count)
            topMargin = oldTop
        }
    }

    fun insertCharacters(count: Int) {
        val r = cursor.row
        val screen = activeScreen
        val num = minOf(count, cols - cursor.col)
        for (c in cols - 1 downTo cursor.col + num) {
            screen[r][c].copyFrom(screen[r][c - num])
        }
        for (c in cursor.col until cursor.col + num) {
            screen[r][c].reset()
        }
    }

    fun deleteCharacters(count: Int) {
        val r = cursor.row
        val screen = activeScreen
        val num = minOf(count, cols - cursor.col)
        for (c in cursor.col until cols - num) {
            screen[r][c].copyFrom(screen[r][c + num])
        }
        for (c in cols - num until cols) {
            screen[r][c].reset()
        }
    }

    fun clearLine(mode: Int) {
        val r = cursor.row
        val screen = activeScreen
        when (mode) {
            0 -> for (c in cursor.col until cols) screen[r][c].reset()       // Cursor to end
            1 -> for (c in 0..cursor.col) screen[r][c].reset()               // Start to cursor
            2 -> for (c in 0 until cols) screen[r][c].reset()                // Entire line
        }
    }

    fun clearScreen(mode: Int) {
        val screen = activeScreen
        when (mode) {
            0 -> { // Cursor to bottom
                clearLine(0)
                for (r in cursor.row + 1 until rows) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
            }
            1 -> { // Top to cursor
                for (r in 0 until cursor.row) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
                clearLine(1)
            }
            2, 3 -> { // Entire screen
                for (r in 0 until rows) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
                if (mode == 3) {
                    scrollback.clear()
                }
                cursor.col = 0
                cursor.row = 0
            }
        }
    }

    fun writeChar(codePoint: Int, currentFg: Int, currentBg: Int, currentFlags: Int) {
        val screen = activeScreen
        if (cursor.col >= cols) {
            if (autowrap) {
                cursor.col = 0
                if (cursor.row >= bottomMargin) {
                    scrollUp(1)
                } else {
                    cursor.row++
                }
            } else {
                cursor.col = cols - 1
            }
        }

        val cell = screen[cursor.row][cursor.col]
        cell.codePoint = codePoint
        cell.fgColor = currentFg
        cell.bgColor = currentBg
        cell.flags = currentFlags
        cell.width = if (Character.charCount(codePoint) > 1 || codePoint > 0x2E80) 2 else 1

        cursor.col += cell.width
    }
}
