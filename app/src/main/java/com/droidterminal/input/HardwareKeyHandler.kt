package com.droidterminal.input

import android.view.KeyEvent

/**
 * Intercepts and translates hardware keyboard events (USB, Bluetooth)
 * into terminal byte sequences, preserving all standard Linux shortcuts.
 */
class HardwareKeyHandler(
    private val onSendBytes: (ByteArray) -> Unit,
    private val isApplicationCursorMode: () -> Boolean
) {
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val appCursor = isApplicationCursorMode()

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ENTER))
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_BACKSPACE))
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_TAB))
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ESC))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onSendBytes(TerminalKeyEncoder.getArrowUp(appCursor))
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onSendBytes(TerminalKeyEncoder.getArrowDown(appCursor))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onSendBytes(TerminalKeyEncoder.getArrowRight(appCursor))
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onSendBytes(TerminalKeyEncoder.getArrowLeft(appCursor))
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                onSendBytes(TerminalKeyEncoder.HOME)
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                onSendBytes(TerminalKeyEncoder.END)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                onSendBytes(TerminalKeyEncoder.PAGE_UP)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                onSendBytes(TerminalKeyEncoder.PAGE_DOWN)
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onSendBytes(TerminalKeyEncoder.DELETE)
                return true
            }
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                val fIndex = keyCode - KeyEvent.KEYCODE_F1 + 1
                onSendBytes(TerminalKeyEncoder.getFunctionKey(fIndex))
                return true
            }
        }

        // Handle printable characters and Ctrl/Alt modifier combinations
        val unicodeChar = event.getUnicodeChar(event.metaState)
        if (unicodeChar != 0) {
            val ch = unicodeChar.toChar()
            val bytes = TerminalKeyEncoder.encodeKey(ch, ctrl, alt, shift)
            onSendBytes(bytes)
            return true
        }

        return false
    }
}
