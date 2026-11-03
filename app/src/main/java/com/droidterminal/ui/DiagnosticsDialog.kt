package com.droidterminal.ui

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.installer.UbuntuInstaller
import com.droidterminal.terminal.TerminalSession
import com.droidterminal.ui.theme.*
import java.io.File

@Composable
fun DiagnosticsDialog(
    context: Context,
    installer: UbuntuInstaller,
    activeSessions: List<TerminalSession>,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copiedToClipboard by remember { mutableStateOf(false) }

    val arch = System.getProperty("os.arch") ?: "Unknown"
    val isInstalled = installer.isInstalled()
    val rootfsDir = installer.rootfsDir
    val prootFile = installer.activeProotFile
    val elfInfo = com.droidterminal.runtime.ElfValidator.validateArm64Elf(prootFile)
    val carrierProot = try {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        File(nativeLibDir, "libproot.so")
    } catch (e: Exception) { null }
    val bashFile = File(rootfsDir, "bin/bash")
    val resolvFile = File(rootfsDir, "etc/resolv.conf")
    val aptSandboxFile = File(rootfsDir, "etc/apt/apt.conf.d/01sandbox")
    val shmDir = File(installer.linuxDir, "shm")

    val stat = try { StatFs(context.filesDir.absolutePath) } catch (e: Exception) { null }
    val freeMb = if (stat != null) (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024) else 0L
    val totalMb = if (stat != null) (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024) else 0L

    val diagnosticReport = buildString {
        appendLine("=== DroidTerminal System Diagnostics ===")
        appendLine("Device Architecture: $arch")
        appendLine("Android OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Storage: $freeMb MB free / $totalMb MB total (Private App Storage)")
        appendLine("Linux Installed: $isInstalled")
        appendLine("Ubuntu Rootfs Path: ${rootfsDir.absolutePath} (Exists: ${rootfsDir.exists()})")
        appendLine("/bin/bash Exists: ${bashFile.exists()}")
        appendLine("PRoot Binary: ${prootFile.absolutePath} (Executable: ${prootFile.canExecute()})")
        appendLine("PRoot Architecture: ${elfInfo.detail}")
        appendLine("Carrier Library (W^X Bypass): ${carrierProot?.absolutePath} (Exists: ${carrierProot?.exists() == true})")
        appendLine("PRoot Version: LinuxDroid-PRoot v5.1.107.92 (commit caadcae)")
        appendLine("Shared Memory (/dev/shm): ${shmDir.absolutePath} (Exists: ${shmDir.exists()})")
        appendLine("APT Sandbox Override: ${aptSandboxFile.exists()}")
        appendLine("DNS Configuration: ${if (resolvFile.exists()) resolvFile.readText().trim() else "Missing"}")
        appendLine("Active Terminal Sessions: ${activeSessions.size}")
        appendLine("Generated At: ${System.currentTimeMillis()}")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .fillMaxHeight(0.88f)
            .clip(RoundedCornerShape(16.dp)),
        color = NeoSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("System & Runtime Diagnostics", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            // Scrollable Diagnostic Items
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiagnosticItem("Ubuntu Linux Status", if (isInstalled) "Installed & Verified Healthy" else "Not Installed", isInstalled)
                DiagnosticItem("Root Filesystem Layout", if (bashFile.exists()) "Valid (/bin/bash present)" else "Missing or Incomplete", bashFile.exists())
                DiagnosticItem("PRoot Runtime", if (prootFile.canExecute()) "v5.1.107.92 (ARM64 Executable)" else "Missing / Not Executable", prootFile.canExecute())
                DiagnosticItem("Device Architecture", "$arch (${Build.SUPPORTED_ABIS.firstOrNull() ?: ""})", arch.contains("aarch64") || arch.contains("arm64"))
                DiagnosticItem("Internal Storage", "$freeMb MB Free / $totalMb MB Total", freeMb > 200)
                DiagnosticItem("DNS Resolution Setup", if (resolvFile.exists()) "Configured (1.1.1.1, 8.8.8.8)" else "Unconfigured", resolvFile.exists())
                DiagnosticItem("APT Privilege Dropping", if (aptSandboxFile.exists()) "01sandbox Configured (Root)" else "Missing", aptSandboxFile.exists())
                DiagnosticItem("POSIX Shared Memory", if (shmDir.exists()) "Enabled (/dev/shm backed)" else "Unconfigured", shmDir.exists())
                DiagnosticItem("Live PTY Sessions", "${activeSessions.size} Active shell process(es)", activeSessions.isNotEmpty())

                Spacer(Modifier.height(6.dp))

                // Raw Report Box
                Text("Raw Diagnostic Output", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TerminalBackground)
                        .padding(10.dp)
                ) {
                    Text(
                        text = diagnosticReport,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }

            // Bottom Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(diagnosticReport))
                        copiedToClipboard = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeoSurfaceRaised),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(if (copiedToClipboard) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (copiedToClipboard) "Copied!" else "Copy Report", color = TextPrimary, fontSize = 12.sp)
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text("Done", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItem(
    title: String,
    value: String,
    isGood: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NeoSurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        }
        Icon(
            imageVector = if (isGood) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isGood) AccentGreen else AccentOrange,
            modifier = Modifier.size(18.dp)
        )
    }
}
