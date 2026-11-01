package com.droidterminal.terminal

class TerminalParser(
    val buffer: TerminalBuffer,
    var onTitleChanged: ((String) -> Unit)? = null,
    var onBell: (() -> Unit)? = null
) {
    private enum class State {
        GROUND,
        ESCAPE,
        CSI_ENTRY,
        CSI_PARAM,
        OSC_STRING
    }

    private var state = State.GROUND
    private val params = IntArray(16)
    private var paramCount = 0
    private var isPrivateMode = false
    private val oscAccumulator = StringBuilder()

    // Active SGR state
    private var currentFg = TerminalCell.DEFAULT_FG
    private var currentBg = TerminalCell.DEFAULT_BG
    private var currentFlags = 0

    // Standard 16 ANSI Palette (XTerm defaults)
    companion object {
        val ANSI_COLORS = intArrayOf(
            0xFF000000.toInt(), // 0: Black
            0xFFCD0000.toInt(), // 1: Red
            0xFF00CD00.toInt(), // 2: Green
            0xFFCDCD00.toInt(), // 3: Yellow
            0xFF0000EE.toInt(), // 4: Blue
            0xFFCD00CD.toInt(), // 5: Magenta
            0xFF00CDCD.toInt(), // 6: Cyan
            0xFFE5E5E5.toInt(), // 7: White
            0xFF7F7F7F.toInt(), // 8: Bright Black (Gray)
            0xFFFF0000.toInt(), // 9: Bright Red
            0xFF00FF00.toInt(), // 10: Bright Green
            0xFFFFFF00.toInt(), // 11: Bright Yellow
            0xFF5C5CFF.toInt(), // 12: Bright Blue
            0xFFFF00FF.toInt(), // 13: Bright Magenta
            0xFF00FFFF.toInt(), // 14: Bright Cyan
            0xFFFFFFFF.toInt()  // 15: Bright White
        )

        fun get256Color(index: Int): Int {
            if (index in 0..15) return ANSI_COLORS[index]
            if (index in 16..231) {
                // 6x6x6 color cube
                val i = index - 16
                val r = (i / 36) * 51
                val g = ((i % 36) / 6) * 51
                val b = (i % 6) * 51
                return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            if (index in 232..255) {
                // Grayscale ramp
                val gray = 8 + (index - 232) * 10
                return 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
            return TerminalCell.DEFAULT_FG
        }
    }

    /**
     * Feed raw bytes into the terminal state machine.
     */
    fun processBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF

            // UTF-8 Sequence handling
            val codePoint: Int
            val charLen: Int
            if (b and 0x80 == 0) {
                codePoint = b
                charLen = 1
            } else if (b and 0xE0 == 0xC0 && i + 1 < end) {
                codePoint = ((b and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)
                charLen = 2
            } else if (b and 0xF0 == 0xE0 && i + 2 < end) {
                codePoint = ((b and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or (bytes[i + 2].toInt() and 0x3F)
                charLen = 3
            } else if (b and 0xF8 == 0xF0 && i + 3 < end) {
                codePoint = ((b and 0x07) shl 18) or ((bytes[i + 1].toInt() and 0x3F) shl 12) or ((bytes[i + 2].toInt() and 0x3F) shl 6) or (bytes[i + 3].toInt() and 0x3F)
                charLen = 4
            } else {
                codePoint = b
                charLen = 1
            }

            processCodePoint(codePoint)
            i += charLen
        }
    }

    private fun processCodePoint(cp: Int) {
        when (state) {
            State.GROUND -> {
                when (cp) {
                    0x1B -> state = State.ESCAPE
                    0x0D -> buffer.cursor.col = 0 // CR
                    0x0A -> {                     // LF
                        if (buffer.cursor.row >= buffer.bottomMargin) {
                            buffer.scrollUp(1)
                        } else {
                            buffer.cursor.row++
                        }
                    }
                    0x08 -> {                     // BS
                        if (buffer.cursor.col > 0) buffer.cursor.col--
                    }
                    0x09 -> {                     // TAB (advance to multiple of 8)
                        val nextTab = (buffer.cursor.col / 8 + 1) * 8
                        buffer.cursor.col = minOf(nextTab, buffer.cols - 1)
                    }
                    0x07 -> onBell?.invoke()      // BEL
                    else -> {
                        if (cp >= 0x20) {
                            buffer.writeChar(cp, currentFg, currentBg, currentFlags)
                        }
                    }
                }
            }

            State.ESCAPE -> {
                when (cp) {
                    '['.code -> {
                        state = State.CSI_ENTRY
                        paramCount = 0
                        isPrivateMode = false
                        for (idx in params.indices) params[idx] = 0
                    }
                    ']'.code -> {
                        state = State.OSC_STRING
                        oscAccumulator.clear()
                    }
                    '7'.code -> { // Save cursor (DECSC)
                        buffer.cursor.save()
                        state = State.GROUND
                    }
                    '8'.code -> { // Restore cursor (DECRC)
                        buffer.cursor.restore(buffer.cols, buffer.rows)
                        state = State.GROUND
                    }
                    'M'.code -> { // Reverse index (scroll down if at top)
                        if (buffer.cursor.row <= buffer.topMargin) {
                            buffer.scrollDown(1)
                        } else {
                            buffer.cursor.row--
                        }
                        state = State.GROUND
                    }
                    'c'.code -> { // Reset terminal
                        buffer.clearScreen(2)
                        currentFg = TerminalCell.DEFAULT_FG
                        currentBg = TerminalCell.DEFAULT_BG
                        currentFlags = 0
                        state = State.GROUND
                    }
                    else -> state = State.GROUND
                }
            }

            State.CSI_ENTRY -> {
                if (cp == '?'.code) {
                    isPrivateMode = true
                } else if (cp in '0'.code..'9'.code) {
                    params[0] = cp - '0'.code
                    paramCount = 1
                    state = State.CSI_PARAM
                } else if (cp == ';'.code) {
                    paramCount = 2
                    state = State.CSI_PARAM
                } else {
                    executeCsi(cp)
                    state = State.GROUND
                }
            }

            State.CSI_PARAM -> {
                if (cp in '0'.code..'9'.code) {
                    val idx = (paramCount - 1).coerceAtLeast(0)
                    params[idx] = params[idx] * 10 + (cp - '0'.code)
                } else if (cp == ';'.code) {
                    if (paramCount < params.size) {
                        paramCount++
                    }
                } else {
                    executeCsi(cp)
                    state = State.GROUND
                }
            }

            State.OSC_STRING -> {
                if (cp == 0x07 || cp == 0x1B) { // BEL or ESC terminates OSC
                    handleOscString(oscAccumulator.toString())
                    state = State.GROUND
                } else {
                    oscAccumulator.append(Character.toChars(cp))
                }
            }
        }
    }

    private fun getParam(index: Int, defaultVal: Int): Int {
        if (index >= paramCount || params[index] == 0) return defaultVal
        return params[index]
    }

    private fun executeCsi(cmd: Int) {
        when (cmd.toChar()) {
            'A' -> buffer.cursor.row = (buffer.cursor.row - getParam(0, 1)).coerceAtLeast(buffer.topMargin)
            'B' -> buffer.cursor.row = (buffer.cursor.row + getParam(0, 1)).coerceAtMost(buffer.bottomMargin)
            'C' -> buffer.cursor.col = (buffer.cursor.col + getParam(0, 1)).coerceAtMost(buffer.cols - 1)
            'D' -> buffer.cursor.col = (buffer.cursor.col - getParam(0, 1)).coerceAtLeast(0)
            'E' -> {
                buffer.cursor.row = (buffer.cursor.row + getParam(0, 1)).coerceAtMost(buffer.bottomMargin)
                buffer.cursor.col = 0
            }
            'F' -> {
                buffer.cursor.row = (buffer.cursor.row - getParam(0, 1)).coerceAtLeast(buffer.topMargin)
                buffer.cursor.col = 0
            }
            'G' -> buffer.cursor.col = (getParam(0, 1) - 1).coerceIn(0, buffer.cols - 1)
            'H', 'f' -> {
                val r = (getParam(0, 1) - 1).coerceIn(0, buffer.rows - 1)
                val c = (getParam(1, 1) - 1).coerceIn(0, buffer.cols - 1)
                buffer.cursor.row = r
                buffer.cursor.col = c
            }
            'J' -> buffer.clearScreen(getParam(0, 0))
            'K' -> buffer.clearLine(getParam(0, 0))
            'L' -> buffer.insertLines(getParam(0, 1))
            'M' -> buffer.deleteLines(getParam(0, 1))
            'P' -> buffer.deleteCharacters(getParam(0, 1))
            '@' -> buffer.insertCharacters(getParam(0, 1))
            'X' -> {
                val num = getParam(0, 1)
                val r = buffer.cursor.row
                for (c in buffer.cursor.col until minOf(buffer.cols, buffer.cursor.col + num)) {
                    buffer.activeScreen[r][c].reset()
                }
            }
            'r' -> {
                val top = getParam(0, 1) - 1
                val bottom = getParam(1, buffer.rows) - 1
                buffer.setMargins(top, bottom)
            }
            's' -> buffer.cursor.save()
            'u' -> buffer.cursor.restore(buffer.cols, buffer.rows)
            'm' -> handleSgr()
            'h' -> handleMode(true)
            'l' -> handleMode(false)
        }
    }

    private fun handleMode(enable: Boolean) {
        val mode = getParam(0, 0)
        if (isPrivateMode) {
            when (mode) {
                1 -> buffer.applicationCursorMode = enable      // DECCKM
                25 -> buffer.cursor.visible = enable            // DECTCEM
                1049 -> buffer.switchAlternateScreen(enable)    // Alternate screen
                2004 -> buffer.bracketedPasteMode = enable      // Bracketed paste
            }
        }
    }

    private fun handleSgr() {
        if (paramCount == 0) {
            currentFg = TerminalCell.DEFAULT_FG
            currentBg = TerminalCell.DEFAULT_BG
            currentFlags = 0
            return
        }

        var idx = 0
        while (idx < paramCount) {
            when (val code = params[idx]) {
                0 -> {
                    currentFg = TerminalCell.DEFAULT_FG
                    currentBg = TerminalCell.DEFAULT_BG
                    currentFlags = 0
                }
                1 -> currentFlags = currentFlags or TerminalCell.FLAG_BOLD
                2 -> currentFlags = currentFlags or TerminalCell.FLAG_DIM
                3 -> currentFlags = currentFlags or TerminalCell.FLAG_ITALIC
                4 -> currentFlags = currentFlags or TerminalCell.FLAG_UNDERLINE
                5 -> currentFlags = currentFlags or TerminalCell.FLAG_BLINK
                7 -> currentFlags = currentFlags or TerminalCell.FLAG_INVERSE
                8 -> currentFlags = currentFlags or TerminalCell.FLAG_HIDDEN
                9 -> currentFlags = currentFlags or TerminalCell.FLAG_STRIKETHROUGH
                22 -> currentFlags = currentFlags and (TerminalCell.FLAG_BOLD or TerminalCell.FLAG_DIM).inv()
                23 -> currentFlags = currentFlags and TerminalCell.FLAG_ITALIC.inv()
                24 -> currentFlags = currentFlags and TerminalCell.FLAG_UNDERLINE.inv()
                25 -> currentFlags = currentFlags and TerminalCell.FLAG_BLINK.inv()
                27 -> currentFlags = currentFlags and TerminalCell.FLAG_INVERSE.inv()
                29 -> currentFlags = currentFlags and TerminalCell.FLAG_STRIKETHROUGH.inv()

                in 30..37 -> currentFg = ANSI_COLORS[code - 30]
                38 -> { // Extended foreground: 38;5;idx or 38;2;r;g;b
                    if (idx + 2 < paramCount && params[idx + 1] == 5) {
                        currentFg = get256Color(params[idx + 2])
                        idx += 2
                    } else if (idx + 4 < paramCount && params[idx + 1] == 2) {
                        currentFg = 0xFF000000.toInt() or (params[idx + 2] shl 16) or (params[idx + 3] shl 8) or params[idx + 4]
                        idx += 4
                    }
                }
                39 -> currentFg = TerminalCell.DEFAULT_FG

                in 40..47 -> currentBg = ANSI_COLORS[code - 40]
                48 -> { // Extended background: 48;5;idx or 48;2;r;g;b
                    if (idx + 2 < paramCount && params[idx + 1] == 5) {
                        currentBg = get256Color(params[idx + 2])
                        idx += 2
                    } else if (idx + 4 < paramCount && params[idx + 1] == 2) {
                        currentBg = 0xFF000000.toInt() or (params[idx + 2] shl 16) or (params[idx + 3] shl 8) or params[idx + 4]
                        idx += 4
                    }
                }
                49 -> currentBg = TerminalCell.DEFAULT_BG

                in 90..97 -> currentFg = ANSI_COLORS[code - 90 + 8] // High-intensity fg
                in 100..107 -> currentBg = ANSI_COLORS[code - 100 + 8] // High-intensity bg
            }
            idx++
        }
    }

    private fun handleOscString(osc: String) {
        val semiIdx = osc.indexOf(';')
        if (semiIdx > 0) {
            val cmd = osc.substring(0, semiIdx).toIntOrNull() ?: return
            val param = osc.substring(semiIdx + 1)
            when (cmd) {
                0, 2 -> onTitleChanged?.invoke(param)
            }
        }
    }
}
