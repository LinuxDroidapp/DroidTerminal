package com.droidterminal.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.installer.InstallStage
import com.droidterminal.installer.InstallState
import com.droidterminal.installer.UbuntuInstaller
import com.droidterminal.ui.theme.*

@Composable
fun InstallationScreen(
    installer: UbuntuInstaller,
    onInstallationComplete: () -> Unit,
    onUseLinuxDroidRequested: ((String) -> Unit)? = null
) {
    val state by installer.state.collectAsState()

    LaunchedEffect(Unit) {
        if (!installer.isInstalled() && !state.isComplete && state.stage == InstallStage.NOT_INSTALLED) {
            installer.startInstallation(onInstallationComplete)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeoBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(NeoSurface)
                .border(1.dp, NeoLightShadow.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header / Brand Badge
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(NeoSurfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "DroidTerminal Linux",
                    tint = AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = "Setting Up Ubuntu Linux",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Configuring genuine Ubuntu ARM64 userspace via PRoot.\nThis initial setup occurs only once.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            // LinuxDroid Detection Card (if found)
            if (state.detectedLinuxDroidPath != null && onUseLinuxDroidRequested != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentCyan.copy(alpha = 0.1f))
                        .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Existing LinuxDroid Installation Detected",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Found Ubuntu rootfs at:\n${state.detectedLinuxDroidPath}\nYou can link this installation to save disk space.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { onUseLinuxDroidRequested(state.detectedLinuxDroidPath!!) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text("Reuse Existing LinuxDroid Environment", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Overall Progress Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.stage.title,
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (state.progressFraction >= 0f) {
                        Text(
                            text = "${(state.progressFraction * 100).toInt()}%",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (state.progressFraction >= 0f) {
                    LinearProgressIndicator(
                        progress = { state.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = NeoDarkShadow
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = NeoDarkShadow
                    )
                }

                Text(
                    text = state.detailMessage,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Stepper / Stage Checklist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeoSurfaceRaised)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val stages = listOf(
                    InstallStage.CHECKING_DEVICE,
                    InstallStage.PREPARING_STORAGE,
                    InstallStage.DOWNLOADING_ROOTFS,
                    InstallStage.VERIFYING_ARCHIVE,
                    InstallStage.EXTRACTING_ROOTFS,
                    InstallStage.PREPARING_PROOT,
                    InstallStage.INITIALIZING_UBUNTU,
                    InstallStage.CONFIGURING_ENVIRONMENT,
                    InstallStage.VERIFYING_INSTALLATION
                )

                stages.forEach { stage ->
                    val isPast = state.stage.stepNumber > stage.stepNumber || state.isComplete
                    val isCurrent = state.stage == stage
                    val isFailed = state.stage == InstallStage.RECOVERY_REQUIRED && state.stage.stepNumber >= stage.stepNumber

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isPast -> AccentGreen
                                        isCurrent -> AccentCyan
                                        isFailed -> ErrorRed
                                        else -> NeoDarkShadow
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPast) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                            } else if (isCurrent) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        Text(
                            text = stage.title,
                            color = when {
                                isPast -> TextPrimary
                                isCurrent -> AccentCyan
                                isFailed -> ErrorRed
                                else -> TextSecondary
                            },
                            fontSize = 12.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Error & Retry Card
            if (state.errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ErrorRed.copy(alpha = 0.1f))
                        .border(1.dp, ErrorRed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Installation Error",
                                color = ErrorRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = state.errorMessage ?: "An unexpected error occurred.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        if (state.canRetry) {
                            Button(
                                onClick = { installer.startInstallation(onInstallationComplete) },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry Installation", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
