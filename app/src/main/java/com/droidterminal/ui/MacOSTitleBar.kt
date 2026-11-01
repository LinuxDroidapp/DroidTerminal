package com.droidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.ui.theme.*

@Composable
fun MacOSTitleBar(
    title: String,
    runtimeBadge: String,
    isKeyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onNewTab: () -> Unit,
    onSplitHorizontal: () -> Unit,
    onSplitVertical: () -> Unit,
    onSettings: () -> Unit,
    onCloseWindow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MacTitleBarBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // macOS Traffic Lights
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MacTrafficClose)
                    .clickable { onCloseWindow() }
            )
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MacTrafficMinimize)
                    .clickable { onToggleKeyboard() }
            )
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MacTrafficMaximize)
                    .clickable { onSplitVertical() }
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Session Title & Runtime Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeoBorder)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = runtimeBadge,
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action Toolbar
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNewTab, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New Tab", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSplitHorizontal, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.VerticalSplit, contentDescription = "Split Horizontal", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSplitVertical, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.HorizontalSplit, contentDescription = "Split Vertical", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onToggleKeyboard, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "Toggle Keyboard",
                    tint = if (isKeyboardVisible) AccentCyan else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
