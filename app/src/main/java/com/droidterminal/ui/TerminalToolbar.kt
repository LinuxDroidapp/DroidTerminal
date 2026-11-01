package com.droidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.ui.theme.*

@Composable
fun TerminalToolbar(
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
    onToggleIme: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(NeoSurfaceRaised)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarButton("Copy", Icons.Default.ContentCopy, onClick = onCopy)
        ToolbarButton("Paste", Icons.Default.ContentPaste, onClick = onPaste)
        ToolbarButton("Clear", Icons.Default.DeleteOutline, onClick = onClear)
        ToolbarButton("Top", Icons.Default.VerticalAlignTop, onClick = onScrollTop)
        ToolbarButton("Bottom", Icons.Default.VerticalAlignBottom, onClick = onScrollBottom)
        ToolbarButton("IME", Icons.Default.Keyboard, onClick = onToggleIme)
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = TextPrimary, fontSize = 11.sp)
    }
}
