package com.droidterminal.terminal

/**
 * High-performance terminal cell representation.
 * Packed representation to optimize cache locality and memory usage.
 */
data class TerminalCell(
    var codePoint: Int = ' '.code,
    var fgColor: Int = DEFAULT_FG,
    var bgColor: Int = DEFAULT_BG,
    var flags: Int = 0,
    var width: Byte = 1
) {
    companion object {
        const val FLAG_BOLD: Int = 1 shl 0
        const val FLAG_DIM: Int = 1 shl 1
        const val FLAG_ITALIC: Int = 1 shl 2
        const val FLAG_UNDERLINE: Int = 1 shl 3
        const val FLAG_BLINK: Int = 1 shl 4
        const val FLAG_INVERSE: Int = 1 shl 5
        const val FLAG_HIDDEN: Int = 1 shl 6
        const val FLAG_STRIKETHROUGH: Int = 1 shl 7
        const val FLAG_URL: Int = 1 shl 8

        // Default theme colors (ARGB)
        const val DEFAULT_FG: Int = 0xFFE3E8F1.toInt()
        const val DEFAULT_BG: Int = 0x00000000 // Transparent (background rendered by surface)
        const val SELECTION_BG: Int = 0x6638BDF8.toInt()
        const val CURSOR_COLOR: Int = 0xFF38BDF8.toInt()
    }

    fun isBold(): Boolean = (flags and FLAG_BOLD) != 0
    fun isDim(): Boolean = (flags and FLAG_DIM) != 0
    fun isItalic(): Boolean = (flags and FLAG_ITALIC) != 0
    fun isUnderline(): Boolean = (flags and FLAG_UNDERLINE) != 0
    fun isBlink(): Boolean = (flags and FLAG_BLINK) != 0
    fun isInverse(): Boolean = (flags and FLAG_INVERSE) != 0
    fun isHidden(): Boolean = (flags and FLAG_HIDDEN) != 0
    fun isStrikethrough(): Boolean = (flags and FLAG_STRIKETHROUGH) != 0

    fun reset() {
        codePoint = ' '.code
        fgColor = DEFAULT_FG
        bgColor = DEFAULT_BG
        flags = 0
        width = 1
    }

    fun copyFrom(other: TerminalCell) {
        this.codePoint = other.codePoint
        this.fgColor = other.fgColor
        this.bgColor = other.bgColor
        this.flags = other.flags
        this.width = other.width
    }
}

enum class CursorShape {
    BLOCK,
    UNDERLINE,
    BAR
}

data class CursorState(
    var col: Int = 0,
    var row: Int = 0,
    var visible: Boolean = true,
    var shape: CursorShape = CursorShape.BLOCK,
    var blinking: Boolean = true,
    var savedCol: Int = 0,
    var savedRow: Int = 0
) {
    fun save() {
        savedCol = col
        savedRow = row
    }

    fun restore(maxCols: Int, maxRows: Int) {
        col = savedCol.coerceIn(0, (maxCols - 1).coerceAtLeast(0))
        row = savedRow.coerceIn(0, (maxRows - 1).coerceAtLeast(0))
    }
}
