package com.droidterminal.input

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.ui.theme.*

/**
 * Three-state modifier lifecycle:
 * - OFF: Inactive.
 * - ONE_SHOT: Tapped once, consumed on next completed keypress.
 * - LOCKED: Long-pressed, persistently latched across keypresses until tapped to unlock.
 * Strictly avoids double-tap locking.
 */
enum class ModifierState {
    OFF,
    ONE_SHOT,
    LOCKED;

    val isActive: Boolean get() = this != OFF
    val isLocked: Boolean get() = this == LOCKED

    fun toggleOnTap(): ModifierState = when (this) {
        OFF -> ONE_SHOT
        ONE_SHOT -> OFF
        LOCKED -> OFF
    }

    fun latchOnLongPress(): ModifierState = LOCKED

    fun consume(): ModifierState = when (this) {
        ONE_SHOT -> OFF
        LOCKED -> LOCKED
        OFF -> OFF
    }
}

/**
 * Neomorphic laptop-style keycap component supporting primary glyph,
 * secondary shifted label, Fn secondary mapping, and dual tap/long-press gestures.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LaptopKeycap(
    label: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    badge: String? = null,
    height: Dp = 38.dp,
    state: ModifierState = ModifierState.OFF,
    isCapsActive: Boolean = false,
    fontSize: Int = 12,
    enableHaptics: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDepressed = isPressed || state.isActive || isCapsActive

    val borderColor = when {
        state.isLocked -> AccentGreen
        state.isActive || isCapsActive -> AccentCyan
        else -> Color.Transparent
    }

    val textColor = when {
        state.isLocked -> AccentGreen
        state.isActive || isCapsActive -> AccentCyan
        isDepressed -> AccentGreen
        else -> TextPrimary
    }

    Box(
        modifier = modifier
            .height(height)
            .drawBehind {
                if (!isDepressed) {
                    drawRect(
                        color = NeoLightShadow,
                        topLeft = Offset(-1.5f, -1.5f),
                        size = size
                    )
                    drawRect(
                        color = NeoDarkShadow,
                        topLeft = Offset(2f, 2f),
                        size = size
                    )
                }
            }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDepressed) NeoDarkShadow else NeoKeycapSurface)
            .then(
                if (state.isActive || isCapsActive) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(6.dp))
                } else Modifier
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (enableHaptics) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onClick()
                },
                onLongClick = onLongClick?.let { action ->
                    {
                        if (enableHaptics) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        action()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(1.dp)
        ) {
            // Optional shifted sub-label or badge
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    color = if (state.isActive) AccentCyan else TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal
                )
            }
            // Primary label with lock badge if latched
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = if (isDepressed) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                if (state.isLocked || isCapsActive) {
                    Text(
                        text = "🔒",
                        fontSize = 8.sp,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            if (badge != null) {
                Text(
                    text = badge,
                    color = AccentCyan,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Laptop-Style QWERTY On-Screen Keyboard.
 *
 * Strict Layout:
 * - Row 0: ` ~ | 1-0 | - _ | = + | ⌫ (Fn: DEL)
 * - Row 1: [TAB] Q W E R T Y U I O P [ { ] } \ | (Fn: INS)
 * - Row 2: [CAPS] A S D F G H J K L ; : ' " [ENTER ⏎]
 * - Row 3: Exactly ONE Shift on left: [SHIFT] Z X C V B N M , < . > / ?
 * - Row 4: Exactly: [CTRL] [FN] [ALT] [  SPACEBAR  ] [ ARROW CLUSTER (Inverted-T) ]
 *
 * Modifier Rules:
 * - Shift, Ctrl, Alt, Fn: Tap -> One-Shot (consumed on next key). Long-Press -> Persistent Lock. Tap while locked -> Unlocks.
 * - NO double-tap locking.
 * - Fn reveals temporary F1-F12 layer. One-shot Fn hides layer after use; locked Fn keeps it open.
 * - Fn secondary mappings: Left->Home, Right->End, Up->PgUp, Down->PgDn, Backspace->Delete, \->Insert.
 */
@Composable
fun LinuxKeyboard(
    visible: Boolean,
    isAppCursorMode: Boolean,
    onHideRequested: () -> Unit,
    onToggleImeRequested: () -> Unit,
    onSendBytes: (ByteArray) -> Unit
) {
    var ctrlState by remember { mutableStateOf(ModifierState.OFF) }
    var altState by remember { mutableStateOf(ModifierState.OFF) }
    var shiftState by remember { mutableStateOf(ModifierState.OFF) }
    var fnState by remember { mutableStateOf(ModifierState.OFF) }
    var isCapsLockOn by remember { mutableStateOf(false) }

    fun consumeOneShotModifiers() {
        ctrlState = ctrlState.consume()
        altState = altState.consume()
        shiftState = shiftState.consume()
        fnState = fnState.consume()
    }

    val isShiftEffective = (shiftState.isActive) xor isCapsLockOn

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(NeoSurface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Accessory Strip: ESC, active status badges, IME switch, and Keyboard hide
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LaptopKeycap(
                        label = "ESC",
                        modifier = Modifier.width(42.dp),
                        height = 26.dp,
                        fontSize = 10,
                        onClick = {
                            onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ESC))
                            consumeOneShotModifiers()
                        }
                    )
                    // Modifier status indicators
                    if (ctrlState.isActive) {
                        Text("CTRL${if (ctrlState.isLocked) "🔒" else ""}", color = AccentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (altState.isActive) {
                        Text("ALT${if (altState.isLocked) "🔒" else ""}", color = AccentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (shiftState.isActive) {
                        Text("SHIFT${if (shiftState.isLocked) "🔒" else ""}", color = AccentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (isCapsLockOn) {
                        Text("CAPS🔒", color = AccentGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (fnState.isActive) {
                        Text("FN${if (fnState.isLocked) "🔒" else ""}", color = AccentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleImeRequested, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Android Keyboard", tint = AccentCyan, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onHideRequested, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardHide, contentDescription = "Hide Keyboard", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Function Key Layer (F1-F12) - Revealed when Fn is active
            AnimatedVisibility(
                visible = fnState.isActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (f in 1..12) {
                        LaptopKeycap(
                            label = "F$f",
                            modifier = Modifier.weight(1f),
                            height = 30.dp,
                            fontSize = 9,
                            onClick = {
                                onSendBytes(TerminalKeyEncoder.getFunctionKey(f))
                                consumeOneShotModifiers()
                            }
                        )
                    }
                }
            }

            // Row 0: ` ~ | 1-0 | - _ | = + | ⌫ (Fn: DEL)
            val row0Keys = listOf(
                Pair("`", "~"), Pair("1", "!"), Pair("2", "@"), Pair("3", "#"),
                Pair("4", "$"), Pair("5", "%"), Pair("6", "^"), Pair("7", "&"),
                Pair("8", "*"), Pair("9", "("), Pair("0", ")"), Pair("-", "_"),
                Pair("=", "+")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                row0Keys.forEach { (norm, shifted) ->
                    val displayChar = if (isShiftEffective) shifted else norm
                    val sub = if (isShiftEffective) norm else shifted
                    LaptopKeycap(
                        label = displayChar,
                        subLabel = sub,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 11,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayChar.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }

                // Backspace (Fn secondary mapping: Delete)
                LaptopKeycap(
                    label = if (fnState.isActive) "DEL" else "⌫",
                    badge = if (!fnState.isActive) "DEL" else null,
                    modifier = Modifier.weight(1.3f),
                    height = 36.dp,
                    fontSize = 11,
                    onClick = {
                        if (fnState.isActive) {
                            onSendBytes(TerminalKeyEncoder.DELETE)
                        } else {
                            onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_BACKSPACE))
                        }
                        consumeOneShotModifiers()
                    }
                )
            }

            // Row 1: [TAB] Q W E R T Y U I O P [ ] \ (Fn: INS)
            val qwertyLetters = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                LaptopKeycap(
                    label = "TAB",
                    modifier = Modifier.weight(1.2f),
                    height = 36.dp,
                    fontSize = 10,
                    onClick = {
                        onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_TAB))
                        consumeOneShotModifiers()
                    }
                )
                qwertyLetters.forEach { letter ->
                    val displayLetter = if (isShiftEffective) letter.uppercase() else letter
                    LaptopKeycap(
                        label = displayLetter,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 12,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayLetter.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
                // Brackets
                listOf(Pair("[", "{"), Pair("]", "}")).forEach { (norm, shifted) ->
                    val displayChar = if (isShiftEffective) shifted else norm
                    val sub = if (isShiftEffective) norm else shifted
                    LaptopKeycap(
                        label = displayChar,
                        subLabel = sub,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 11,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayChar.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
                // Backslash (Fn secondary mapping: Insert)
                val backslashDisplay = if (isShiftEffective) "|" else "\\"
                val backslashSub = if (isShiftEffective) "\\" else "|"
                LaptopKeycap(
                    label = if (fnState.isActive) "INS" else backslashDisplay,
                    subLabel = if (!fnState.isActive) backslashSub else null,
                    badge = if (!fnState.isActive) "INS" else null,
                    modifier = Modifier.weight(1.1f),
                    height = 36.dp,
                    fontSize = 10,
                    onClick = {
                        if (fnState.isActive) {
                            onSendBytes(TerminalKeyEncoder.INSERT)
                        } else {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = backslashDisplay.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                        }
                        consumeOneShotModifiers()
                    }
                )
            }

            // Row 2: [CAPS] A S D F G H J K L ; ' [ENTER ⏎]
            val homeLetters = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                LaptopKeycap(
                    label = "CAPS",
                    isCapsActive = isCapsLockOn,
                    modifier = Modifier.weight(1.3f),
                    height = 36.dp,
                    fontSize = 9,
                    onClick = {
                        isCapsLockOn = !isCapsLockOn
                    }
                )
                homeLetters.forEach { letter ->
                    val displayLetter = if (isShiftEffective) letter.uppercase() else letter
                    LaptopKeycap(
                        label = displayLetter,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 12,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayLetter.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
                // Semicolon and Quote
                listOf(Pair(";", ":"), Pair("'", "\"")).forEach { (norm, shifted) ->
                    val displayChar = if (isShiftEffective) shifted else norm
                    val sub = if (isShiftEffective) norm else shifted
                    LaptopKeycap(
                        label = displayChar,
                        subLabel = sub,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 11,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayChar.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
                // Enter
                LaptopKeycap(
                    label = "ENTER ⏎",
                    modifier = Modifier.weight(1.7f),
                    height = 36.dp,
                    fontSize = 10,
                    onClick = {
                        onSendBytes(byteArrayOf(TerminalKeyEncoder.CODE_ENTER))
                        consumeOneShotModifiers()
                    }
                )
            }

            // Row 3: Exactly ONE Shift on left: [SHIFT] Z X C V B N M , < . > / ?
            val zRowLetters = listOf("z", "x", "c", "v", "b", "n", "m")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                LaptopKeycap(
                    label = "SHIFT",
                    state = shiftState,
                    modifier = Modifier.weight(1.8f),
                    height = 36.dp,
                    fontSize = 10,
                    onClick = {
                        shiftState = shiftState.toggleOnTap()
                    },
                    onLongClick = {
                        shiftState = shiftState.latchOnLongPress()
                    }
                )
                zRowLetters.forEach { letter ->
                    val displayLetter = if (isShiftEffective) letter.uppercase() else letter
                    LaptopKeycap(
                        label = displayLetter,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 12,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayLetter.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
                // Punctuation , . /
                listOf(Pair(",", "<"), Pair(".", ">"), Pair("/", "?")).forEach { (norm, shifted) ->
                    val displayChar = if (isShiftEffective) shifted else norm
                    val sub = if (isShiftEffective) norm else shifted
                    LaptopKeycap(
                        label = displayChar,
                        subLabel = sub,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                        fontSize = 11,
                        onClick = {
                            val bytes = TerminalKeyEncoder.encodeKey(
                                char = displayChar.first(),
                                ctrl = ctrlState.isActive,
                                alt = altState.isActive,
                                shift = false
                            )
                            onSendBytes(bytes)
                            consumeOneShotModifiers()
                        }
                    )
                }
            }

            // Row 4: Exactly: [CTRL] [FN] [ALT] [  SPACEBAR  ] [ ARROW CLUSTER (Inverted-T) ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Single CTRL key
                LaptopKeycap(
                    label = "CTRL",
                    state = ctrlState,
                    modifier = Modifier.weight(1.1f),
                    height = 42.dp,
                    fontSize = 10,
                    onClick = {
                        ctrlState = ctrlState.toggleOnTap()
                    },
                    onLongClick = {
                        ctrlState = ctrlState.latchOnLongPress()
                    }
                )

                // Single FN key
                LaptopKeycap(
                    label = "FN",
                    state = fnState,
                    modifier = Modifier.weight(1.0f),
                    height = 42.dp,
                    fontSize = 10,
                    onClick = {
                        fnState = fnState.toggleOnTap()
                    },
                    onLongClick = {
                        fnState = fnState.latchOnLongPress()
                    }
                )

                // Single ALT key
                LaptopKeycap(
                    label = "ALT",
                    state = altState,
                    modifier = Modifier.weight(1.1f),
                    height = 42.dp,
                    fontSize = 10,
                    onClick = {
                        altState = altState.toggleOnTap()
                    },
                    onLongClick = {
                        altState = altState.latchOnLongPress()
                    }
                )

                // Wide Spacebar
                LaptopKeycap(
                    label = "SPACEBAR",
                    modifier = Modifier.weight(3.8f),
                    height = 42.dp,
                    fontSize = 11,
                    onClick = {
                        val bytes = TerminalKeyEncoder.encodeKey(
                            char = ' ',
                            ctrl = ctrlState.isActive,
                            alt = altState.isActive,
                            shift = false
                        )
                        onSendBytes(bytes)
                        consumeOneShotModifiers()
                    }
                )

                // True Laptop-Style Inverted-T Arrow Cluster
                //        [ ↑ PgUp ]
                // [ ← Home ] [ ↓ PgDn ] [ → End ]
                Column(
                    modifier = Modifier
                        .width(108.dp)
                        .height(42.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top half: Up Arrow
                    LaptopKeycap(
                        label = "↑",
                        subLabel = if (fnState.isActive) "PgUp" else null,
                        modifier = Modifier
                            .width(34.dp)
                            .weight(1f),
                        height = 20.dp,
                        fontSize = 10,
                        onClick = {
                            if (fnState.isActive) {
                                onSendBytes(TerminalKeyEncoder.PAGE_UP)
                            } else {
                                onSendBytes(TerminalKeyEncoder.getArrowUp(isAppCursorMode))
                            }
                            consumeOneShotModifiers()
                        }
                    )

                    // Bottom half: Left, Down, Right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        LaptopKeycap(
                            label = "←",
                            subLabel = if (fnState.isActive) "Home" else null,
                            modifier = Modifier.weight(1f),
                            height = 20.dp,
                            fontSize = 10,
                            onClick = {
                                if (fnState.isActive) {
                                    onSendBytes(TerminalKeyEncoder.HOME)
                                } else {
                                    onSendBytes(TerminalKeyEncoder.getArrowLeft(isAppCursorMode))
                                }
                                consumeOneShotModifiers()
                            }
                        )
                        LaptopKeycap(
                            label = "↓",
                            subLabel = if (fnState.isActive) "PgDn" else null,
                            modifier = Modifier.weight(1f),
                            height = 20.dp,
                            fontSize = 10,
                            onClick = {
                                if (fnState.isActive) {
                                    onSendBytes(TerminalKeyEncoder.PAGE_DOWN)
                                } else {
                                    onSendBytes(TerminalKeyEncoder.getArrowDown(isAppCursorMode))
                                }
                                consumeOneShotModifiers()
                            }
                        )
                        LaptopKeycap(
                            label = "→",
                            subLabel = if (fnState.isActive) "End" else null,
                            modifier = Modifier.weight(1f),
                            height = 20.dp,
                            fontSize = 10,
                            onClick = {
                                if (fnState.isActive) {
                                    onSendBytes(TerminalKeyEncoder.END)
                                } else {
                                    onSendBytes(TerminalKeyEncoder.getArrowRight(isAppCursorMode))
                                }
                                consumeOneShotModifiers()
                            }
                        )
                    }
                }
            }
        }
    }
}
