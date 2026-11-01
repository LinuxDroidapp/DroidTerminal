package com.droidterminal.input

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.ui.theme.*

@Composable
fun NeomorphicKeycap(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    isLatched: Boolean = false,
    fontSize: Int = 12,
    enableHaptics: Boolean = true,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDepressed = isPressed || isLatched

    Box(
        modifier = modifier
            .height(height)
            .drawBehind {
                if (!isDepressed) {
                    drawRect(
                        color = NeoLightShadow,
                        topLeft = Offset(-2f, -2f),
                        size = size
                    )
                    drawRect(
                        color = NeoDarkShadow,
                        topLeft = Offset(3f, 3f),
                        size = size
                    )
                }
            }
            .clip(RoundedCornerShape(7.dp))
            .background(if (isDepressed) NeoDarkShadow else NeoKeycapSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (enableHaptics) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isLatched) AccentCyan else if (isDepressed) AccentGreen else TextPrimary,
            fontSize = fontSize.sp,
            fontWeight = if (isLatched || isDepressed) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun LinuxKeyboard(
    visible: Boolean,
    isAppCursorMode: Boolean,
    onHideRequested: () -> Unit,
    onToggleImeRequested: () -> Unit,
    onSendBytes: (ByteArray) -> Unit
) {
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isFnActive by remember { mutableStateOf(false) }
    var isSymbolsActive by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(NeoSurface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Function & Toolbar Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NeomorphicKeycap("ESC", Modifier.width(46.dp), height = 34.dp) {
                        onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ESC))
                    }
                    NeomorphicKeycap("TAB", Modifier.width(46.dp), height = 34.dp) {
                        onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_TAB))
                    }
                    NeomorphicKeycap("CTRL", Modifier.width(54.dp), height = 34.dp, isLatched = isCtrlActive) {
                        isCtrlActive = !isCtrlActive
                    }
                    NeomorphicKeycap("ALT", Modifier.width(46.dp), height = 34.dp, isLatched = isAltActive) {
                        isAltActive = !isAltActive
                    }
                    NeomorphicKeycap("FN", Modifier.width(42.dp), height = 34.dp, isLatched = isFnActive) {
                        isFnActive = !isFnActive
                    }
                    NeomorphicKeycap(if (isSymbolsActive) "ABC" else "?123", Modifier.width(50.dp), height = 34.dp) {
                        isSymbolsActive = !isSymbolsActive
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Switch to Android Soft Keyboard
                    IconButton(
                        onClick = onToggleImeRequested,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Switch to Android Keyboard",
                            tint = AccentCyan
                        )
                    }

                    // Hide Keyboard
                    IconButton(
                        onClick = onHideRequested,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardHide,
                            contentDescription = "Hide Keyboard",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Shell Navigation Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                NeomorphicKeycap("|", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("|".toByteArray()) }
                NeomorphicKeycap("/", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("/".toByteArray()) }
                NeomorphicKeycap("\\", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("\\".toByteArray()) }
                NeomorphicKeycap("-", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("-".toByteArray()) }
                NeomorphicKeycap("~", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("~".toByteArray()) }
                NeomorphicKeycap("`", Modifier.weight(0.9f), height = 36.dp) { onSendBytes("`".toByteArray()) }
                NeomorphicKeycap("▲", Modifier.weight(1f), height = 36.dp) {
                    onSendBytes(TerminalKeyEncoder.getArrowUp(isAppCursorMode))
                }
                NeomorphicKeycap("▼", Modifier.weight(1f), height = 36.dp) {
                    onSendBytes(TerminalKeyEncoder.getArrowDown(isAppCursorMode))
                }
                NeomorphicKeycap("◀", Modifier.weight(1f), height = 36.dp) {
                    onSendBytes(TerminalKeyEncoder.getArrowLeft(isAppCursorMode))
                }
                NeomorphicKeycap("▶", Modifier.weight(1f), height = 36.dp) {
                    onSendBytes(TerminalKeyEncoder.getArrowRight(isAppCursorMode))
                }
            }

            // Alpha or Symbol Row 1
            val row1 = if (isFnActive) {
                listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10")
            } else if (isSymbolsActive) {
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            } else {
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row1.forEachIndexed { index, key ->
                    NeomorphicKeycap(key, Modifier.weight(1f)) {
                        val bytes = if (isFnActive) {
                            TerminalKeyEncoder.getFunctionKey(index + 1)
                        } else {
                            TerminalKeyEncoder.encodeKey(key.first(), isCtrlActive, isAltActive, isShiftActive)
                        }
                        onSendBytes(bytes)
                        if (isCtrlActive) isCtrlActive = false
                        if (isAltActive) isAltActive = false
                    }
                }
            }

            // Alpha or Symbol Row 2
            val row2 = if (isSymbolsActive) {
                listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
            } else {
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row2.forEach { key ->
                    NeomorphicKeycap(key, Modifier.weight(1f)) {
                        val bytes = TerminalKeyEncoder.encodeKey(key.first(), isCtrlActive, isAltActive, isShiftActive)
                        onSendBytes(bytes)
                        if (isCtrlActive) isCtrlActive = false
                        if (isAltActive) isAltActive = false
                    }
                }
            }

            // Alpha or Symbol Row 3
            val row3 = if (isSymbolsActive) {
                listOf("[", "]", "{", "}", ";", ":", "'", "\"", "<", ">")
            } else {
                listOf("z", "x", "c", "v", "b", "n", "m")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                NeomorphicKeycap("⇧", Modifier.weight(1.3f), isLatched = isShiftActive) {
                    isShiftActive = !isShiftActive
                }
                row3.forEach { key ->
                    NeomorphicKeycap(key, Modifier.weight(1f)) {
                        val bytes = TerminalKeyEncoder.encodeKey(key.first(), isCtrlActive, isAltActive, isShiftActive)
                        onSendBytes(bytes)
                        if (isCtrlActive) isCtrlActive = false
                        if (isAltActive) isAltActive = false
                    }
                }
                NeomorphicKeycap("⌫", Modifier.weight(1.3f)) {
                    onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_BACKSPACE))
                }
            }

            // Bottom Space & Enter Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                NeomorphicKeycap("HOME", Modifier.weight(1.2f)) { onSendBytes(TerminalKeyEncoder.HOME) }
                NeomorphicKeycap("SPACE", Modifier.weight(3.5f)) { onSendBytes(byteArrayOf(0x20)) }
                NeomorphicKeycap("END", Modifier.weight(1.2f)) { onSendBytes(TerminalKeyEncoder.END) }
                NeomorphicKeycap("RETURN ⏎", Modifier.weight(2f)) { onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ENTER)) }
            }
        }
    }
}
