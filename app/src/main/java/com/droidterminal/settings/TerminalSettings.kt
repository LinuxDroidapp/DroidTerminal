package com.droidterminal.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.terminal.CursorShape
import com.droidterminal.ui.theme.*

class TerminalSettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("droidterm_prefs", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat("font_size", 13f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    var cursorShape: CursorShape
        get() {
            val name = prefs.getString("cursor_shape", CursorShape.BLOCK.name)
            return try { CursorShape.valueOf(name ?: CursorShape.BLOCK.name) } catch (e: Exception) { CursorShape.BLOCK }
        }
        set(value) = prefs.edit().putString("cursor_shape", value.name).apply()

    var cursorBlinking: Boolean
        get() = prefs.getBoolean("cursor_blinking", true)
        set(value) = prefs.edit().putBoolean("cursor_blinking", value).apply()

    var enableHaptics: Boolean
        get() = prefs.getBoolean("enable_haptics", true)
        set(value) = prefs.edit().putBoolean("enable_haptics", value).apply()

    var defaultShell: String
        get() = prefs.getString("default_shell", "/bin/bash") ?: "/bin/bash"
        set(value) = prefs.edit().putString("default_shell", value).apply()

    var scrollbackLimit: Int
        get() = prefs.getInt("scrollback_limit", 10000)
        set(value) = prefs.edit().putInt("scrollback_limit", value).apply()
}

@Composable
fun SettingsDialog(
    settings: TerminalSettingsManager,
    onDismiss: () -> Unit,
    onOpenDiagnostics: (() -> Unit)? = null,
    onRepairLinux: (() -> Unit)? = null
) {
    var fontSize by remember { mutableFloatStateOf(settings.fontSizeSp) }
    var blinking by remember { mutableStateOf(settings.cursorBlinking) }
    var haptics by remember { mutableStateOf(settings.enableHaptics) }

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.85f)
            .clip(RoundedCornerShape(16.dp)),
        color = NeoSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("DroidTerminal Settings", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Appearance & Typography", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font Size: ${fontSize.toInt()} sp", color = TextPrimary, fontSize = 13.sp)
                    Slider(
                        value = fontSize,
                        onValueChange = {
                            fontSize = it
                            settings.fontSizeSp = it
                        },
                        valueRange = 10f..24f,
                        steps = 14,
                        modifier = Modifier.width(180.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cursor Blinking", color = TextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = blinking,
                        onCheckedChange = {
                            blinking = it
                            settings.cursorBlinking = it
                        }
                    )
                }

                HorizontalDivider(color = NeoBorder)

                Text("Input & Keyboard", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Haptic Feedback on Key Tap", color = TextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = haptics,
                        onCheckedChange = {
                            haptics = it
                            settings.enableHaptics = it
                        }
                    )
                }

                HorizontalDivider(color = NeoBorder)

                Text("Linux Runtime & Userspace", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Environment: Genuine Ubuntu ARM64 via PRoot", color = TextSecondary, fontSize = 12.sp)
                Text("Default Shell: /bin/bash (Login Shell)", color = TextSecondary, fontSize = 12.sp)
                Text("TERM: xterm-256color", color = TextSecondary, fontSize = 12.sp)
                Text("COLORTERM: truecolor", color = TextSecondary, fontSize = 12.sp)
                Text("Scrollback History: 10,000 lines", color = TextSecondary, fontSize = 12.sp)

                if (onOpenDiagnostics != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onOpenDiagnostics,
                        colors = ButtonDefaults.buttonColors(containerColor = NeoSurfaceRaised),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Runtime Diagnostics", color = TextPrimary, fontSize = 12.sp)
                    }
                }

                if (onRepairLinux != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onRepairLinux,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reinstall / Repair Linux Environment", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
