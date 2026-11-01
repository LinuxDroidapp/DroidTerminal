package com.droidterminal.input

/**
 * Encodes keystrokes into POSIX ASCII bytes and VT100/xterm escape sequences.
 * Strictly respects terminal application modes such as DECCKM (Application Cursor Keys).
 */
object TerminalKeyEncoder {

    const val CODE_ESC: Byte = 0x1B
    const val CODE_TAB: Byte = 0x09
    const val CODE_ENTER: Byte = 0x0D
    const val CODE_BACKSPACE: Byte = 0x7F

    // Navigation keys (Normal mode)
    val ARROW_UP_NORMAL = "\u001B[A".toByteArray(Charsets.US_ASCII)
    val ARROW_DOWN_NORMAL = "\u001B[B".toByteArray(Charsets.US_ASCII)
    val ARROW_RIGHT_NORMAL = "\u001B[C".toByteArray(Charsets.US_ASCII)
    val ARROW_LEFT_NORMAL = "\u001B[D".toByteArray(Charsets.US_ASCII)

    // Navigation keys (Application Cursor Mode - DECCKM ?1h)
    val ARROW_UP_APP = "\u001BOA".toByteArray(Charsets.US_ASCII)
    val ARROW_DOWN_APP = "\u001BOB".toByteArray(Charsets.US_ASCII)
    val ARROW_RIGHT_APP = "\u001BOC".toByteArray(Charsets.US_ASCII)
    val ARROW_LEFT_APP = "\u001BOD".toByteArray(Charsets.US_ASCII)

    // Editing & Scrolling keys
    val HOME = "\u001B[H".toByteArray(Charsets.US_ASCII)
    val END = "\u001B[F".toByteArray(Charsets.US_ASCII)
    val INSERT = "\u001B[2~".toByteArray(Charsets.US_ASCII)
    val DELETE = "\u001B[3~".toByteArray(Charsets.US_ASCII)
    val PAGE_UP = "\u001B[5~".toByteArray(Charsets.US_ASCII)
    val PAGE_DOWN = "\u001B[6~".toByteArray(Charsets.US_ASCII)

    // Function keys (F1 through F12)
    val FUNCTION_KEYS = arrayOf(
        "\u001BOP".toByteArray(Charsets.US_ASCII),   // F1
        "\u001BOQ".toByteArray(Charsets.US_ASCII),   // F2
        "\u001BOR".toByteArray(Charsets.US_ASCII),   // F3
        "\u001BOS".toByteArray(Charsets.US_ASCII),   // F4
        "\u001B[15~".toByteArray(Charsets.US_ASCII), // F5
        "\u001B[17~".toByteArray(Charsets.US_ASCII), // F6
        "\u001B[18~".toByteArray(Charsets.US_ASCII), // F7
        "\u001B[19~".toByteArray(Charsets.US_ASCII), // F8
        "\u001B[20~".toByteArray(Charsets.US_ASCII), // F9
        "\u001B[21~".toByteArray(Charsets.US_ASCII), // F10
        "\u001B[23~".toByteArray(Charsets.US_ASCII), // F11
        "\u001B[24~".toByteArray(Charsets.US_ASCII)  // F12
    )

    fun getArrowUp(appCursorMode: Boolean): ByteArray = if (appCursorMode) ARROW_UP_APP else ARROW_UP_NORMAL
    fun getArrowDown(appCursorMode: Boolean): ByteArray = if (appCursorMode) ARROW_DOWN_APP else ARROW_DOWN_NORMAL
    fun getArrowRight(appCursorMode: Boolean): ByteArray = if (appCursorMode) ARROW_RIGHT_APP else ARROW_RIGHT_NORMAL
    fun getArrowLeft(appCursorMode: Boolean): ByteArray = if (appCursorMode) ARROW_LEFT_APP else ARROW_LEFT_NORMAL

    fun getFunctionKey(index: Int): ByteArray {
        if (index in 1..12) {
            return FUNCTION_KEYS[index - 1]
        }
        return byteArrayOf()
    }

    /**
     * Converts a character and modifier state into the exact byte sequence.
     */
    fun encodeKey(
        char: Char,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean
    ): ByteArray {
        val c = if (shift) char.uppercaseChar() else char

        if (ctrl) {
            val upper = c.uppercaseChar()
            val code = when (upper) {
                in '@'..'_' -> (upper.code and 0x1F).toByte()
                '?' -> 0x7F.toByte()
                '2' -> 0x00.toByte() // NUL
                '3' -> 0x1B.toByte() // ESC
                '4' -> 0x1C.toByte() // FS
                '5' -> 0x1D.toByte() // GS
                '6' -> 0x1E.toByte() // RS
                '7' -> 0x1F.toByte() // US
                '8' -> 0x7F.toByte() // DEL
                else -> (upper.code and 0x1F).toByte()
            }
            return if (alt) byteArrayOf(CODE_ESC, code) else byteArrayOf(code)
        }

        val utf8 = c.toString().toByteArray(Charsets.UTF_8)
        return if (alt) byteArrayOf(CODE_ESC) + utf8 else utf8
    }

    /**
     * Wraps text for bracketed paste if active.
     */
    fun wrapBracketedPaste(text: String, isBracketedPasteActive: Boolean): ByteArray {
        return if (isBracketedPasteActive) {
            val start = "\u001B[200~".toByteArray(Charsets.US_ASCII)
            val content = text.toByteArray(Charsets.UTF_8)
            val end = "\u001B[201~".toByteArray(Charsets.US_ASCII)
            start + content + end
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
    }
}
