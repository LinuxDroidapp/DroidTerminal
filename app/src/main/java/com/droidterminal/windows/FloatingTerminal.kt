package com.droidterminal.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.terminal.TerminalRenderer
import com.droidterminal.terminal.TerminalSession
import com.droidterminal.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun FloatingTerminalWindow(
    session: TerminalSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(60f) }
    var offsetY by remember { mutableFloatStateOf(100f) }
    var widthDp by remember { mutableStateOf(320.dp) }
    var heightDp by remember { mutableStateOf(240.dp) }
    var isMaximized by remember { mutableStateOf(false) }
    var isMinimized by remember { mutableStateOf(false) }

    if (isMinimized) {
        // Compact pill mode
        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            colors = CardDefaults.cardColors(containerColor = NeoSurfaceRaised),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Floating: ${session.title}", color = TextPrimary, fontSize = 12.sp)
                TextButton(onClick = { isMinimized = false }) {
                    Text("Restore", fontSize = 11.sp, color = AccentCyan)
                }
            }
        }
        return
    }

    val windowModifier = if (isMaximized) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .shadow(16.dp, RoundedCornerShape(12.dp))
            .border(1.dp, NeoBorder, RoundedCornerShape(12.dp))
    }

    Column(
        modifier = windowModifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeoSurface)
    ) {
        // Draggable Title Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MacTitleBarBg)
                .pointerInput(isMaximized) {
                    if (!isMaximized) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Floating: ${session.title}",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { isMinimized = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Minimize, contentDescription = "Minimize", tint = TextSecondary, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { isMaximized = !isMaximized }, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Maximize",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = MacTrafficClose, modifier = Modifier.size(14.dp))
            }
        }

        // Terminal Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBackground)
        ) {
            TerminalRenderer(
                session = session,
                onTap = {}
            )
        }
    }
}
