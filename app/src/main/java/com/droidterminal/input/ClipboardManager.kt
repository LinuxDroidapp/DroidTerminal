package com.droidterminal.input

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context

class ClipboardManager(private val context: Context) {

    private val clipboard: AndroidClipboard by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboard
    }

    fun copyText(text: String, label: String = "DroidTerminal") {
        if (text.isEmpty()) return
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    fun getClipboardText(): String? {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context)
            return text?.toString()
        }
        return null
    }

    fun paste(isBracketedPaste: Boolean, onSendBytes: (ByteArray) -> Unit) {
        val text = getClipboardText() ?: return
        val bytes = TerminalKeyEncoder.wrapBracketedPaste(text, isBracketedPaste)
        onSendBytes(bytes)
    }
}
