package com.droidterminal.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.droidterminal.R
import com.droidterminal.ui.theme.AccentCyan
import com.droidterminal.ui.theme.NeoDarkShadow
import kotlin.math.floor

@Composable
fun TerminalRenderer(
    session: TerminalSession,
    fontSizeSp: Float = 13f,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val jetbrainsTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        } catch (e: Throwable) {
            Typeface.MONOSPACE
        }
    }

    // Trigger re-render whenever session updates
    val updateGen = session.updateCounter

    // Cursor Blink Animation
    val infiniteTransition = rememberInfiniteTransition(label = "CursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CursorAlpha"
    )

    // Scroll offset in lines (0 = live view at bottom, > 0 = viewing scrollback history)
    var scrollOffsetLines by remember { mutableIntStateOf(0) }

    var measuredCharWidth by remember { mutableFloatStateOf(0f) }
    var measuredLineHeight by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(session.id) {
                    detectTapGestures(
                        onTap = {
                            session.selection.clear()
                            onTap()
                        },
                        onDoubleTap = { offset ->
                            if (measuredCharWidth > 0 && measuredLineHeight > 0) {
                                val col = (offset.x / measuredCharWidth).toInt().coerceIn(0, session.cols - 1)
                                val row = (offset.y / measuredLineHeight).toInt().coerceIn(0, session.rows - 1)
                                // Select word around (col, row)
                                synchronized(session.buffer) {
                                    val screen = session.buffer.activeScreen
                                    var startC = col
                                    while (startC > 0 && screen[row][startC - 1].codePoint != 0 && screen[row][startC - 1].codePoint != ' '.code) {
                                        startC--
                                    }
                                    var endC = col
                                    while (endC < session.cols - 1 && screen[row][endC + 1].codePoint != 0 && screen[row][endC + 1].codePoint != ' '.code) {
                                        endC++
                                    }
                                    session.selection.start(startC, row)
                                    session.selection.update(endC, row)
                                }
                            }
                        },
                        onLongPress = { offset ->
                            if (measuredCharWidth > 0 && measuredLineHeight > 0) {
                                val col = (offset.x / measuredCharWidth).toInt().coerceIn(0, session.cols - 1)
                                val row = (offset.y / measuredLineHeight).toInt().coerceIn(0, session.rows - 1)
                                session.selection.start(col, row)
                            }
                        }
                    )
                }
                .pointerInput(session.id) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (session.selection.isActive && measuredCharWidth > 0 && measuredLineHeight > 0) {
                                val col = (offset.x / measuredCharWidth).toInt().coerceIn(0, session.cols - 1)
                                val row = (offset.y / measuredLineHeight).toInt().coerceIn(0, session.rows - 1)
                                session.selection.update(col, row)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (session.selection.isActive && measuredCharWidth > 0 && measuredLineHeight > 0) {
                                val col = (change.position.x / measuredCharWidth).toInt().coerceIn(0, session.cols - 1)
                                val row = (change.position.y / measuredLineHeight).toInt().coerceIn(0, session.rows - 1)
                                session.selection.update(col, row)
                            } else {
                                // Touch scroll gesture: drag up scrolls down history, drag down scrolls up history
                                if (dragAmount.y > 6) {
                                    scrollOffsetLines = (scrollOffsetLines + 1).coerceAtMost(session.buffer.scrollback.size())
                                } else if (dragAmount.y < -6) {
                                    scrollOffsetLines = (scrollOffsetLines - 1).coerceAtLeast(0)
                                }
                            }
                        }
                    )
                }
        ) {
            val density = this.density
            val fontSizePx = fontSizeSp * density

            val textPaint = Paint().apply {
                textSize = fontSizePx
                isAntiAlias = true
                typeface = jetbrainsTypeface
                color = android.graphics.Color.WHITE
            }

            val charWidth = textPaint.measureText("M")
            val fontMetrics = textPaint.fontMetrics
            val lineHeight = fontMetrics.bottom - fontMetrics.top + 2f

            measuredCharWidth = charWidth
            measuredLineHeight = lineHeight

            val availableCols = floor(size.width / charWidth).toInt().coerceAtLeast(10)
            val availableRows = floor(size.height / lineHeight).toInt().coerceAtLeast(4)

            if (availableCols != session.cols || availableRows != session.rows) {
                session.resize(availableCols, availableRows)
            }

            drawIntoCanvas { composeCanvas ->
                val nativeCanvas = composeCanvas.nativeCanvas

                synchronized(session.buffer) {
                    val screen = session.buffer.activeScreen
                    val rows = session.rows
                    val cols = session.cols
                    val scrollbackSize = session.buffer.scrollback.size()
                    val searchMatches = session.searchState.matches
                    val activeMatchIdx = session.searchState.currentMatchIndex

                    // 1. Render Lines (Scrollback or Active Screen)
                    for (r in 0 until rows) {
                        val yTop = r * lineHeight
                        val yBaseline = yTop - fontMetrics.top

                        // Map display line `r` to history or screen
                        val isScrollbackLine: Boolean
                        val lineIndex: Int
                        val lineCells: Array<TerminalCell>?

                        if (scrollOffsetLines > 0) {
                            val globalIndex = (scrollbackSize - scrollOffsetLines) + r
                            if (globalIndex < scrollbackSize) {
                                isScrollbackLine = true
                                lineIndex = globalIndex
                                lineCells = session.buffer.scrollback.getLine(globalIndex)
                            } else {
                                isScrollbackLine = false
                                lineIndex = globalIndex - scrollbackSize
                                lineCells = if (lineIndex in 0 until rows) screen[lineIndex] else null
                            }
                        } else {
                            isScrollbackLine = false
                            lineIndex = r
                            lineCells = screen[r]
                        }

                        if (lineCells == null) continue

                        for (c in 0 until minOf(cols, lineCells.size)) {
                            val cell = lineCells[c]
                            val xLeft = c * charWidth

                            // Selection highlight
                            val isSelected = !isScrollbackLine && session.selection.isSelected(c, r)

                            // Search match highlight
                            var isSearchMatch = false
                            var isCurrentMatch = false
                            if (searchMatches.isNotEmpty()) {
                                for (mIdx in searchMatches.indices) {
                                    val m = searchMatches[mIdx]
                                    if (m.isScrollback == isScrollbackLine && m.lineIndex == lineIndex && c in m.colStart until (m.colStart + m.length)) {
                                        isSearchMatch = true
                                        if (mIdx == activeMatchIdx) {
                                            isCurrentMatch = true
                                        }
                                        break
                                    }
                                }
                            }

                            // Draw cell background
                            if (isCurrentMatch) {
                                nativeCanvas.drawRect(
                                    xLeft, yTop, xLeft + charWidth, yTop + lineHeight,
                                    Paint().apply { color = 0xFFFF9800.toInt() }
                                )
                            } else if (isSearchMatch) {
                                nativeCanvas.drawRect(
                                    xLeft, yTop, xLeft + charWidth, yTop + lineHeight,
                                    Paint().apply { color = 0x80FFEB3B.toInt() }
                                )
                            } else if (isSelected) {
                                nativeCanvas.drawRect(
                                    xLeft, yTop, xLeft + charWidth, yTop + lineHeight,
                                    Paint().apply { color = TerminalCell.SELECTION_BG }
                                )
                            } else if (cell.bgColor != TerminalCell.DEFAULT_BG && cell.bgColor != 0) {
                                nativeCanvas.drawRect(
                                    xLeft, yTop, xLeft + charWidth, yTop + lineHeight,
                                    Paint().apply { color = cell.bgColor }
                                )
                            }

                            // Draw glyph
                            if (cell.codePoint != 0 && cell.codePoint != ' '.code) {
                                textPaint.color = if (isCurrentMatch) {
                                    android.graphics.Color.BLACK
                                } else if (cell.isInverse()) {
                                    if (cell.bgColor != 0) cell.bgColor else android.graphics.Color.BLACK
                                } else {
                                    cell.fgColor
                                }
                                textPaint.isFakeBoldText = cell.isBold()
                                textPaint.isUnderlineText = cell.isUnderline()
                                textPaint.isStrikeThruText = cell.isStrikethrough()

                                val chars = Character.toChars(cell.codePoint)
                                nativeCanvas.drawText(chars, 0, chars.size, xLeft, yBaseline, textPaint)
                            }
                        }
                    }

                    // 2. Draw Cursor (only in live bottom view)
                    if (scrollOffsetLines == 0) {
                        val cursor = session.buffer.cursor
                        if (cursor.visible && cursor.row in 0 until rows && cursor.col in 0 until cols) {
                            val cx = cursor.col * charWidth
                            val cy = cursor.row * lineHeight

                            val cursorPaint = Paint().apply {
                                color = TerminalCell.CURSOR_COLOR
                                alpha = (cursorAlpha * 255).toInt()
                            }

                            when (cursor.shape) {
                                CursorShape.BLOCK -> {
                                    nativeCanvas.drawRect(cx, cy, cx + charWidth, cy + lineHeight, cursorPaint)
                                    val curCell = screen[cursor.row][cursor.col]
                                    if (curCell.codePoint != 0 && curCell.codePoint != ' '.code) {
                                        val invPaint = Paint(textPaint).apply {
                                            color = android.graphics.Color.BLACK
                                        }
                                        val chars = Character.toChars(curCell.codePoint)
                                        nativeCanvas.drawText(chars, 0, chars.size, cx, cy - fontMetrics.top, invPaint)
                                    }
                                }
                                CursorShape.UNDERLINE -> {
                                    nativeCanvas.drawRect(cx, cy + lineHeight - 3f, cx + charWidth, cy + lineHeight, cursorPaint)
                                }
                                CursorShape.BAR -> {
                                    nativeCanvas.drawRect(cx, cy, cx + 2.5f, cy + lineHeight, cursorPaint)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Scrollback indicator pill (shows when user scrolled history)
        if (scrollOffsetLines > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeoDarkShadow.copy(alpha = 0.9f))
                    .clickable { scrollOffsetLines = 0 }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "Return to bottom",
                        tint = AccentCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "History: -$scrollOffsetLines • Return",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
