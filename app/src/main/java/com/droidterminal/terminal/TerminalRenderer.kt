package com.droidterminal.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.floor

@Composable
fun TerminalRenderer(
    session: TerminalSession,
    fontSizeSp: Float = 13f,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Re-draw when session updates
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

    // Scroll offset (for viewing scrollback history)
    var scrollOffsetLines by remember { mutableIntStateOf(0) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(session.id) {
                detectTapGestures(
                    onTap = {
                        session.selection.clear()
                        onTap()
                    },
                    onDoubleTap = { offset ->
                        // Double tap: select word
                    },
                    onLongPress = { offset ->
                        // Long press: start selection
                    }
                )
            }
            .pointerInput(session.id) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Selection drag or scroll
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Scroll vertically
                        if (dragAmount.y > 10) {
                            scrollOffsetLines = (scrollOffsetLines + 1).coerceAtMost(session.buffer.scrollback.size())
                        } else if (dragAmount.y < -10) {
                            scrollOffsetLines = (scrollOffsetLines - 1).coerceAtLeast(0)
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
            typeface = Typeface.MONOSPACE
            color = android.graphics.Color.WHITE
        }

        // Measure monospace cell dimensions
        val charWidth = textPaint.measureText("M")
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top + 2f

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

                // 1. Draw Cell Backgrounds & Characters
                for (r in 0 until rows) {
                    val yTop = r * lineHeight
                    val yBaseline = yTop - fontMetrics.top

                    for (c in 0 until cols) {
                        val cell = screen[r][c]
                        val xLeft = c * charWidth

                        val isSelected = session.selection.isSelected(c, r)

                        // Draw background
                        if (isSelected) {
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
                            textPaint.color = if (cell.isInverse()) {
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

                // 2. Draw Cursor
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
                            // Draw character under cursor in inverse color
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
