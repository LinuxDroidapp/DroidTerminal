package com.droidterminal.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidterminal.terminal.TerminalRenderer
import com.droidterminal.terminal.TerminalSession
import com.droidterminal.ui.theme.AccentCyan
import com.droidterminal.ui.theme.NeoBorder
import com.droidterminal.ui.theme.TerminalBackground

sealed class SplitNode {
    data class Leaf(val sessionId: String) : SplitNode()
    data class Horizontal(
        val first: SplitNode,
        val second: SplitNode,
        var ratio: Float = 0.5f
    ) : SplitNode()
    data class Vertical(
        val first: SplitNode,
        val second: SplitNode,
        var ratio: Float = 0.5f
    ) : SplitNode()
}

@Composable
fun RenderSplitTree(
    node: SplitNode,
    sessionsMap: Map<String, TerminalSession>,
    activeSessionId: String,
    onFocusSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (node) {
        is SplitNode.Leaf -> {
            val session = sessionsMap[node.sessionId]
            if (session != null) {
                val isFocused = session.id == activeSessionId
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .background(TerminalBackground)
                        .border(
                            width = if (isFocused) 1.5.dp else 0.5.dp,
                            color = if (isFocused) AccentCyan else NeoBorder
                        )
                        .clickable { onFocusSession(session.id) }
                ) {
                    TerminalRenderer(
                        session = session,
                        onTap = { onFocusSession(session.id) }
                    )
                }
            }
        }
        is SplitNode.Horizontal -> {
            Row(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(node.ratio).fillMaxHeight()) {
                    RenderSplitTree(node.first, sessionsMap, activeSessionId, onFocusSession)
                }
                Spacer(modifier = Modifier.width(2.dp).fillMaxHeight().background(NeoBorder))
                Box(modifier = Modifier.weight(1f - node.ratio).fillMaxHeight()) {
                    RenderSplitTree(node.second, sessionsMap, activeSessionId, onFocusSession)
                }
            }
        }
        is SplitNode.Vertical -> {
            Column(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(node.ratio).fillMaxWidth()) {
                    RenderSplitTree(node.first, sessionsMap, activeSessionId, onFocusSession)
                }
                Spacer(modifier = Modifier.height(2.dp).fillMaxWidth().background(NeoBorder))
                Box(modifier = Modifier.weight(1f - node.ratio).fillMaxWidth()) {
                    RenderSplitTree(node.second, sessionsMap, activeSessionId, onFocusSession)
                }
            }
        }
    }
}
