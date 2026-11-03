package com.droidterminal.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidterminal.terminal.TerminalSearchState
import com.droidterminal.ui.theme.*

@Composable
fun TerminalToolbar(
    searchState: TerminalSearchState?,
    onPerformSearch: (String) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
    onToggleIme: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSearchExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NeoSurfaceRaised)
    ) {
        // Expandable Search Bar
        AnimatedVisibility(
            visible = isSearchExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = AccentCyan,
                    modifier = Modifier.size(16.dp)
                )

                // Inline Search Input
                var queryText by remember { mutableStateOf(searchState?.query ?: "") }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TerminalBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (queryText.isEmpty()) {
                        Text(
                            text = "Find in terminal...",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    BasicTextField(
                        value = queryText,
                        onValueChange = { newQ ->
                            queryText = newQ
                            onPerformSearch(newQ)
                        },
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(AccentCyan),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            searchState?.nextMatch()
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Match count badge
                val matchCount = searchState?.matches?.size ?: 0
                val activeIdx = searchState?.currentMatchIndex ?: -1
                val badgeText = if (matchCount > 0) "${activeIdx + 1}/$matchCount" else if (queryText.isNotEmpty()) "0/0" else ""
                if (badgeText.isNotEmpty()) {
                    Text(
                        text = badgeText,
                        color = if (matchCount > 0) AccentCyan else TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Prev / Next Match
                IconButton(
                    onClick = { searchState?.prevMatch() },
                    enabled = matchCount > 0,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Prev Match", tint = if (matchCount > 0) AccentCyan else TextSecondary, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = { searchState?.nextMatch() },
                    enabled = matchCount > 0,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match", tint = if (matchCount > 0) AccentCyan else TextSecondary, modifier = Modifier.size(16.dp))
                }

                // Close Search
                IconButton(
                    onClick = {
                        isSearchExpanded = false
                        queryText = ""
                        onPerformSearch("")
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Search", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Standard Quick-Actions Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton("Search", Icons.Default.Search) {
                isSearchExpanded = !isSearchExpanded
            }
            ToolbarButton("Copy", Icons.Default.ContentCopy, onClick = onCopy)
            ToolbarButton("Paste", Icons.Default.ContentPaste, onClick = onPaste)
            ToolbarButton("Clear", Icons.Default.DeleteOutline, onClick = onClear)
            ToolbarButton("Top", Icons.Default.VerticalAlignTop, onClick = onScrollTop)
            ToolbarButton("Bottom", Icons.Default.VerticalAlignBottom, onClick = onScrollBottom)
            ToolbarButton("IME", Icons.Default.Keyboard, onClick = onToggleIme)
        }
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
