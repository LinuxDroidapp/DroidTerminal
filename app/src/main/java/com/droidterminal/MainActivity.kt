package com.droidterminal

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.droidterminal.input.ClipboardManager
import com.droidterminal.input.HardwareKeyHandler
import com.droidterminal.input.LinuxKeyboard
import com.droidterminal.runtime.StandaloneRuntimeProvider
import com.droidterminal.service.TerminalSessionService
import com.droidterminal.settings.SettingsDialog
import com.droidterminal.settings.TerminalSettingsManager
import com.droidterminal.terminal.TerminalSession
import com.droidterminal.ui.MacOSTitleBar
import com.droidterminal.ui.TerminalToolbar
import com.droidterminal.ui.theme.*
import com.droidterminal.windows.*

class MainActivity : ComponentActivity() {

    private val sessions = mutableStateListOf<TerminalSession>()
    private var activeSessionId by mutableStateOf("")
    private var splitTree by mutableStateOf<SplitNode?>(null)
    private var floatingSession by mutableStateOf<TerminalSession?>(null)

    private lateinit var settingsManager: TerminalSettingsManager
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var hardwareKeyHandler: HardwareKeyHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = TerminalSettingsManager(applicationContext)
        clipboardManager = ClipboardManager(applicationContext)

        hardwareKeyHandler = HardwareKeyHandler(
            onSendBytes = { bytes -> getActiveSession()?.write(bytes) },
            isApplicationCursorMode = { getActiveSession()?.buffer?.applicationCursorMode == true }
        )

        // Create initial terminal session
        val runtimeProvider = StandaloneRuntimeProvider(applicationContext)
        val initialSession = TerminalSession(
            title = "bash - root@ubuntu",
            runtimeProvider = runtimeProvider,
            cols = 80,
            rows = 24
        )
        sessions.add(initialSession)
        activeSessionId = initialSession.id
        splitTree = SplitNode.Leaf(initialSession.id)

        initialSession.start()
        TerminalSessionService.startService(applicationContext)

        setContent {
            DroidTerminalTheme {
                MainTerminalRootScreen(
                    sessions = sessions,
                    activeSessionId = activeSessionId,
                    splitTree = splitTree,
                    floatingSession = floatingSession,
                    settingsManager = settingsManager,
                    onSelectSession = { id ->
                        activeSessionId = id
                    },
                    onNewTab = {
                        val newSession = TerminalSession(
                            title = "bash (Tab ${sessions.size + 1})",
                            runtimeProvider = runtimeProvider,
                            cols = 80,
                            rows = 24
                        )
                        sessions.add(newSession)
                        activeSessionId = newSession.id
                        splitTree = SplitNode.Leaf(newSession.id)
                        newSession.start()
                    },
                    onCloseSession = { id ->
                        val sessionToClose = sessions.find { it.id == id }
                        sessionToClose?.close()
                        sessions.remove(sessionToClose)
                        if (sessions.isNotEmpty()) {
                            activeSessionId = sessions.last().id
                            splitTree = SplitNode.Leaf(activeSessionId)
                        } else {
                            finish()
                        }
                    },
                    onSplitHorizontal = {
                        val currentActive = getActiveSession()
                        if (currentActive != null) {
                            val newSession = TerminalSession(
                                title = "bash (Split)",
                                runtimeProvider = runtimeProvider,
                                cols = 40,
                                rows = 24
                            )
                            sessions.add(newSession)
                            newSession.start()
                            splitTree = SplitNode.Horizontal(
                                first = splitTree ?: SplitNode.Leaf(currentActive.id),
                                second = SplitNode.Leaf(newSession.id)
                            )
                        }
                    },
                    onSplitVertical = {
                        val currentActive = getActiveSession()
                        if (currentActive != null) {
                            val newSession = TerminalSession(
                                title = "bash (Split)",
                                runtimeProvider = runtimeProvider,
                                cols = 80,
                                rows = 12
                            )
                            sessions.add(newSession)
                            newSession.start()
                            splitTree = SplitNode.Vertical(
                                first = splitTree ?: SplitNode.Leaf(currentActive.id),
                                second = SplitNode.Leaf(newSession.id)
                            )
                        }
                    },
                    onToggleFloating = {
                        if (floatingSession == null) {
                            val fSession = TerminalSession(
                                title = "Floating Window",
                                runtimeProvider = runtimeProvider,
                                cols = 60,
                                rows = 18
                            )
                            fSession.start()
                            floatingSession = fSession
                        } else {
                            floatingSession?.close()
                            floatingSession = null
                        }
                    },
                    onCloseFloating = {
                        floatingSession?.close()
                        floatingSession = null
                    },
                    onCopySelection = {
                        val active = getActiveSession()
                        if (active != null) {
                            val text = active.selection.extractSelectedText(active.buffer)
                            clipboardManager.copyText(text)
                            active.selection.clear()
                        }
                    },
                    onPasteClipboard = {
                        val active = getActiveSession()
                        if (active != null) {
                            clipboardManager.paste(active.buffer.bracketedPasteMode) { bytes ->
                                active.write(bytes)
                            }
                        }
                    },
                    onToggleIme = {
                        toggleAndroidSoftKeyboard()
                    },
                    onCloseApp = {
                        sessions.forEach { it.close() }
                        floatingSession?.close()
                        TerminalSessionService.stopService(applicationContext)
                        finish()
                    }
                )
            }
        }
    }

    private fun getActiveSession(): TerminalSession? {
        return sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull()
    }

    private fun toggleAndroidSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && hardwareKeyHandler.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        sessions.forEach { it.close() }
        floatingSession?.close()
        TerminalSessionService.stopService(applicationContext)
    }
}

@Composable
fun MainTerminalRootScreen(
    sessions: List<TerminalSession>,
    activeSessionId: String,
    splitTree: SplitNode?,
    floatingSession: TerminalSession?,
    settingsManager: TerminalSettingsManager,
    onSelectSession: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseSession: (String) -> Unit,
    onSplitHorizontal: () -> Unit,
    onSplitVertical: () -> Unit,
    onToggleFloating: () -> Unit,
    onCloseFloating: () -> Unit,
    onCopySelection: () -> Unit,
    onPasteClipboard: () -> Unit,
    onToggleIme: () -> Unit,
    onCloseApp: () -> Unit
) {
    var isKeyboardVisible by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val activeSession = sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull()
    val sessionMap = remember(sessions) { sessions.associateBy { it.id } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeoBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(NeoSurface)
        ) {
            // 1. macOS TitleBar
            MacOSTitleBar(
                title = activeSession?.title ?: "bash",
                runtimeBadge = activeSession?.runtimeProvider?.getRuntimeName() ?: "Ubuntu LTS",
                isKeyboardVisible = isKeyboardVisible,
                onToggleKeyboard = { isKeyboardVisible = !isKeyboardVisible },
                onNewTab = onNewTab,
                onSplitHorizontal = onSplitHorizontal,
                onSplitVertical = onSplitVertical,
                onSettings = { showSettingsDialog = true },
                onCloseWindow = onCloseApp
            )

            // 2. Multi-Session Tabs Bar
            TerminalTabs(
                sessions = sessions,
                activeSessionId = activeSessionId,
                onSelectSession = onSelectSession,
                onCloseSession = onCloseSession
            )

            // 3. Quick-action Contextual Toolbar
            TerminalToolbar(
                onCopy = onCopySelection,
                onPaste = onPasteClipboard,
                onClear = { activeSession?.buffer?.clearScreen(2) },
                onScrollTop = {},
                onScrollBottom = {},
                onToggleIme = onToggleIme
            )

            // 4. Main Terminal Viewport (Single Pane or Recursive Split Tree)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBackground)
            ) {
                if (splitTree != null) {
                    RenderSplitTree(
                        node = splitTree,
                        sessionsMap = sessionMap,
                        activeSessionId = activeSessionId,
                        onFocusSession = onSelectSession
                    )
                }
            }

            // 5. In-App Touchscreen Linux Keyboard (Collapsible)
            LinuxKeyboard(
                visible = isKeyboardVisible,
                isAppCursorMode = activeSession?.buffer?.applicationCursorMode == true,
                onHideRequested = { isKeyboardVisible = false },
                onToggleImeRequested = onToggleIme,
                onSendBytes = { bytes -> activeSession?.write(bytes) }
            )
        }

        // 6. Floating In-App Terminal Window (if active)
        floatingSession?.let { fSession ->
            FloatingTerminalWindow(
                session = fSession,
                onClose = onCloseFloating
            )
        }

        // Floating Action Button to restore keyboard when collapsed
        if (!isKeyboardVisible) {
            FloatingActionButton(
                onClick = { isKeyboardVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = NeoSurfaceRaised,
                contentColor = AccentCyan
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = "Open Keyboard")
            }
        }

        // Settings Dialog Modal
        if (showSettingsDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NeoBackground.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                SettingsDialog(
                    settings = settingsManager,
                    onDismiss = { showSettingsDialog = false }
                )
            }
        }
    }
}
