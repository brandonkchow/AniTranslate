package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.example.ui.components.GeminiUsageDashboard
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.utils.RunLogEntry
import com.example.ui.theme.EditorialAmber
import com.example.ui.theme.EditorialCanvas
import com.example.ui.theme.EditorialCrimson
import com.example.ui.theme.EditorialCrimsonBorder
import com.example.ui.theme.EditorialCrimsonLight
import com.example.ui.theme.EditorialCrimsonText
import com.example.ui.theme.EditorialDeepViolet
import com.example.ui.theme.EditorialLavender
import com.example.ui.theme.EditorialSuccess
import com.example.ui.theme.EditorialSurface
import com.example.ui.theme.EditorialSurfaceAlt
import com.example.ui.theme.EditorialSurfaceBorder
import com.example.ui.theme.EditorialTextMuted
import com.example.ui.theme.EditorialTextPrimary
import com.example.ui.theme.EditorialTextSecondary

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAddSlotDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.saveFeedback.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("settings_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "API & Provider Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = EditorialTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = EditorialTextPrimary
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveAllSettings() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EditorialLavender,
                            contentColor = EditorialDeepViolet
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .padding(end = 8.dp)
                            .testTag("save_all_top_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Keys", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorialCanvas
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // First-run Security & BYOK Notice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("security_notice_card"),
                colors = CardDefaults.cardColors(
                    containerColor = EditorialSurfaceAlt
                ),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = EditorialLavender,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "BYOK ENCRYPTED STORAGE & PRIORITY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = EditorialLavender
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Keys are securely persisted on your device. The app runs models in order of priority: Slot #1 runs first; if cooling or busy, fallback to Slot #2.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EditorialTextSecondary
                        )
                    }
                }
            }

            // Gemini API Quota & Rate Limit Telemetry Dashboard
            GeminiUsageDashboard(
                initiallyExpanded = true
            )

            // API Slots Header with Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MODEL PRIORITY & KEYS (${uiState.slots.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = EditorialLavender
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.resetToDefaultRecommendedSlots() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = EditorialTextSecondary)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset Defaults", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { showAddSlotDialog = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("add_slot_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EditorialLavender,
                                contentColor = EditorialDeepViolet
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Slot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    text = "List order is fallback order. Top slot (Priority #1) runs first; if busy or cooling down, the pipeline falls back to the next slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorialTextMuted,
                    lineHeight = 16.sp
                )
            }

            // Slot items list
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                uiState.slots.forEachIndexed { index, slot ->
                    ApiSlotCard(
                        slot = slot,
                        index = index,
                        totalSlots = uiState.slots.size,
                        testResult = testResults[slot.id],
                        onUpdate = { updated -> viewModel.updateSlot(updated) },
                        onDelete = { viewModel.deleteSlot(slot.id) },
                        onMoveUp = { viewModel.moveSlot(index, index - 1) },
                        onMoveDown = { viewModel.moveSlot(index, index + 1) },
                        onTestKey = { viewModel.testSlotKey(slot) }
                    )
                }
            }

            // Prominent Save & Apply Button
            Button(
                onClick = { viewModel.saveAllSettings() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EditorialLavender,
                    contentColor = EditorialDeepViolet
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_and_apply_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save & Apply All Changes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Language Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = EditorialSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "TRANSLATION LANGUAGE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = EditorialLavender
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Source: Japanese (日本語)", style = MaterialTheme.typography.bodyMedium, color = EditorialTextSecondary)
                            Text(text = "Target: English (US)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = EditorialTextPrimary)
                        }
                        Surface(
                            color = EditorialDeepViolet,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "English Only (MVP)",
                                color = EditorialLavender,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Inpaint Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = EditorialSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "INPAINTING & BUBBLE WIPING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = EditorialLavender
                    )

                    // Option 1: Flat fill (MVP)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = true,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Flat Fill (MVP)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EditorialTextPrimary
                            )
                            Text(
                                text = "Fast local outer-ring color sampling with rounded fill. Zero network calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted
                            )
                        }
                    }

                    // Option 2: Replicate LaMa (Disabled)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = false,
                            enabled = false,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Replicate LaMa",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EditorialTextMuted.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Coming next. No network calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted.copy(alpha = 0.4f)
                            )
                        }
                    }

                    // Option 3: fal (Disabled)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = false,
                            enabled = false,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "fal AI Inpainter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EditorialTextMuted.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Coming next. No network calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            // Execution & Debug Logs (Advanced Settings)
            val context = LocalContext.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("execution_logs_card"),
                colors = CardDefaults.cardColors(
                    containerColor = EditorialSurface
                ),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "EXECUTION & EXPORT LOGS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = EditorialLavender
                                )
                                Surface(
                                    color = EditorialDeepViolet,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "LAST 3 RUNS",
                                        color = EditorialLavender,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Keeps diagnostic history for the last 3 runs in storage for debugging.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = { viewModel.loadRunLogs() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Logs", tint = EditorialTextMuted, modifier = Modifier.size(18.dp))
                        }
                    }

                    if (uiState.runLogs.isEmpty()) {
                        Surface(
                            color = EditorialCanvas,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No run logs recorded yet. Run a translation batch or export to see telemetry here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.runLogs.forEach { log ->
                                RunLogItemCard(
                                    log = log,
                                    onView = { viewModel.openLogDetails(log) },
                                    onShare = {
                                        val shareIntent = viewModel.getShareIntentForLog(log.id)
                                        if (shareIntent != null) {
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Run Log #${log.jobId}"))
                                        }
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { viewModel.clearAllLogs() },
                                colors = ButtonDefaults.textButtonColors(contentColor = EditorialCrimsonLight)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Logs", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Log Details Dialog
    if (uiState.activeViewingLogText != null) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissLogDialog() },
            title = {
                Text(
                    text = uiState.activeViewingLogTitle ?: "Run Execution Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EditorialTextPrimary
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Diagnostics, Pipeline Stages & Export History:",
                        style = MaterialTheme.typography.bodySmall,
                        color = EditorialTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Surface(
                        color = EditorialCanvas,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .border(1.dp, EditorialSurfaceBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = uiState.activeViewingLogText ?: "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = EditorialTextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = uiState.activeViewingLogText ?: ""
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EditorialLavender,
                        contentColor = EditorialDeepViolet
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLogDialog() }) {
                    Text("Close", color = EditorialTextMuted)
                }
            },
            containerColor = EditorialSurface
        )
    }

    // Add Slot Dialog
    if (showAddSlotDialog) {
        AddSlotDialog(
            onDismiss = { showAddSlotDialog = false },
            onConfirm = { newSlot ->
                viewModel.addSlot(newSlot)
                showAddSlotDialog = false
            }
        )
    }
}

@Composable
fun RunLogItemCard(
    log: RunLogEntry,
    onView: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = CardDefaults.cardColors(
            containerColor = EditorialCanvas
        ),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = when (log.status) {
                            "COMPLETED" -> EditorialSuccess.copy(alpha = 0.2f)
                            "RUNNING" -> EditorialAmber.copy(alpha = 0.2f)
                            else -> EditorialCrimson.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = log.status,
                            color = when (log.status) {
                                "COMPLETED" -> EditorialSuccess
                                "RUNNING" -> EditorialAmber
                                else -> EditorialCrimsonLight
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = if (log.jobId > 0) "Job #${log.jobId}" else "Run Session",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = EditorialTextPrimary
                    )

                    Text(
                        text = "· ${log.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = EditorialTextMuted,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = log.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorialTextSecondary,
                    fontSize = 11.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = "Export Log",
                        tint = EditorialLavender,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onView,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "View Log",
                        tint = EditorialTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSlotCard(
    slot: ApiSlot,
    index: Int,
    totalSlots: Int,
    testResult: SlotTestResult?,
    onUpdate: (ApiSlot) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTestKey: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slot_card_${slot.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (slot.isInvalidKey) EditorialCrimson.copy(alpha = 0.12f)
            else if (!slot.enabled) EditorialSurface.copy(alpha = 0.5f)
            else EditorialSurface
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (slot.isInvalidKey) EditorialCrimsonBorder
            else if (slot.isCoolingDown()) EditorialAmber
            else EditorialSurfaceBorder.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Row 1: Priority Badge + Role Pill on Left, Reorder Arrows + Switch on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = if (index == 0) EditorialLavender else EditorialDeepViolet,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (index == 0) "Priority #1 (Primary)" else "Priority #${index + 1} (Fallback)",
                            color = if (index == 0) EditorialDeepViolet else EditorialLavender,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        color = EditorialSurfaceAlt,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = slot.role.displayName,
                            fontSize = 11.sp,
                            color = EditorialTextSecondary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move Priority Up",
                            tint = if (index > 0) EditorialLavender else EditorialTextMuted.copy(alpha = 0.25f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalSlots - 1,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move Priority Down",
                            tint = if (index < totalSlots - 1) EditorialLavender else EditorialTextMuted.copy(alpha = 0.25f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = slot.enabled,
                        onCheckedChange = { onUpdate(slot.copy(enabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EditorialLavender,
                            checkedTrackColor = EditorialDeepViolet,
                            uncheckedTrackColor = EditorialCanvas
                        )
                    )
                }
            }

            // Row 2: Provider / Model Title & Status Subtitle
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = slot.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EditorialTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Model: ${slot.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EditorialTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    if (slot.isInvalidKey) {
                        Text(text = "• Invalid Key", color = EditorialCrimsonLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else if (slot.isCoolingDown()) {
                        Text(text = "• Cooldown (${slot.remainingCooldownSeconds()}s)", color = EditorialAmber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Row 3: API Key Input Box
            OutlinedTextField(
                value = slot.apiKey,
                onValueChange = { onUpdate(slot.copy(apiKey = it.trim(), isInvalidKey = false)) },
                label = { Text("API Key") },
                placeholder = { Text("Paste your API key here") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                            tint = EditorialTextMuted
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_key_input_${slot.id}")
            )

            // Row 4: Action Buttons (Test Key + Status badge + Configure Model)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestKey,
                        enabled = slot.apiKey.isNotBlank() && testResult?.isTesting != true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EditorialLavender.copy(alpha = 0.6f))
                    ) {
                        if (testResult?.isTesting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = EditorialLavender)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", fontSize = 12.sp, color = EditorialLavender)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = EditorialLavender, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Key", fontSize = 12.sp, color = EditorialLavender, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Test result chip
                    if (testResult != null && !testResult.isTesting) {
                        Surface(
                            color = if (testResult.isSuccess == true) EditorialSuccess.copy(alpha = 0.15f) else EditorialCrimson.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (testResult.isSuccess == true) EditorialSuccess.copy(alpha = 0.4f) else EditorialCrimsonBorder
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (testResult.isSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (testResult.isSuccess == true) EditorialSuccess else EditorialCrimsonLight,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (testResult.isSuccess == true) "Valid" else "Failed",
                                    color = if (testResult.isSuccess == true) EditorialSuccess else EditorialCrimsonLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Expand / Collapse Details Button
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = EditorialTextSecondary)
                ) {
                    Text(if (isExpanded) "Hide Config" else "Configure Model", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Expanded Configuration options with clean spacing
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Recommended Model Presets Chips
                    if (slot.provider.recommendedModels.isNotEmpty()) {
                        Text(
                            text = "Recommended Model Presets",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EditorialLavender,
                            letterSpacing = 0.5.sp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            slot.provider.recommendedModels.forEach { (modelId, modelTitle) ->
                                Surface(
                                    color = if (slot.model == modelId) EditorialDeepViolet else EditorialCanvas,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (slot.model == modelId) EditorialLavender else EditorialSurfaceBorder.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onUpdate(slot.copy(model = modelId))
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = modelTitle,
                                            fontSize = 12.sp,
                                            fontWeight = if (slot.model == modelId) FontWeight.Bold else FontWeight.Normal,
                                            color = if (slot.model == modelId) EditorialLavender else EditorialTextPrimary
                                        )
                                        if (slot.model == modelId) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = EditorialLavender,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Model String
                    OutlinedTextField(
                        value = slot.model,
                        onValueChange = { onUpdate(slot.copy(model = it)) },
                        label = { Text("Model Identifier") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Label
                    OutlinedTextField(
                        value = slot.label,
                        onValueChange = { onUpdate(slot.copy(label = it)) },
                        label = { Text("Custom Display Label (Optional)") },
                        placeholder = { Text("e.g. NVIDIA Nemotron Super") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Base URL
                    OutlinedTextField(
                        value = slot.baseUrl,
                        onValueChange = { onUpdate(slot.copy(baseUrl = it)) },
                        label = { Text("Base API Endpoint URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Role selector
                    Text(
                        text = "Assigned Role",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = EditorialLavender,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SlotRole.entries.forEach { role ->
                            val isSupported = (role != SlotRole.DETECT_OCR && role != SlotRole.ANY) || slot.isVisionSupported
                            FilterChip(
                                selected = slot.role == role,
                                onClick = { if (isSupported) onUpdate(slot.copy(role = role)) },
                                enabled = isSupported,
                                label = { Text(role.displayName, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EditorialDeepViolet,
                                    selectedLabelColor = EditorialLavender
                                )
                            )
                        }
                    }

                    // Delete Slot button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = EditorialCrimsonLight)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Slot", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Slot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddSlotDialog(
    onDismiss: () -> Unit,
    onConfirm: (ApiSlot) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ApiProvider.OPENROUTER) }
    var label by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(ApiProvider.OPENROUTER.defaultModel) }
    var baseUrl by remember { mutableStateOf(ApiProvider.OPENROUTER.defaultBaseUrl) }
    var role by remember { mutableStateOf(SlotRole.TRANSLATE) }
    var isProviderDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = EditorialSurface,
        title = {
            Text(
                text = "Add Model Slot",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = EditorialTextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Provider Selector
                ExposedDropdownMenuBox(
                    expanded = isProviderDropdownExpanded,
                    onExpandedChange = { isProviderDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider Engine") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isProviderDropdownExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = isProviderDropdownExpanded,
                        onDismissRequest = { isProviderDropdownExpanded = false }
                    ) {
                        ApiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    selectedProvider = provider
                                    model = provider.defaultModel
                                    baseUrl = provider.defaultBaseUrl
                                    role = if (provider == ApiProvider.GROQ || provider == ApiProvider.OPENROUTER) SlotRole.TRANSLATE else SlotRole.ANY
                                    isProviderDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Recommended Presets
                if (selectedProvider.recommendedModels.isNotEmpty()) {
                    Text(
                        text = "Quick Presets:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = EditorialLavender
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        selectedProvider.recommendedModels.forEach { (presetModelId, presetLabel) ->
                            Surface(
                                color = if (model == presetModelId) EditorialDeepViolet else EditorialCanvas,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (model == presetModelId) EditorialLavender else EditorialSurfaceBorder.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = presetModelId
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = presetLabel,
                                        fontSize = 11.sp,
                                        fontWeight = if (model == presetModelId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (model == presetModelId) EditorialLavender else EditorialTextPrimary
                                    )
                                    if (model == presetModelId) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = EditorialLavender,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Label
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Slot Label (Optional)") },
                    placeholder = { Text("e.g. Primary Nemotron") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("Paste API key here") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Model
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Identifier") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Role
                Text(
                    text = "Slot Role",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = EditorialLavender
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SlotRole.entries.forEach { r ->
                        val isSupported = (r != SlotRole.DETECT_OCR && r != SlotRole.ANY) || selectedProvider.isVisionCapable
                        FilterChip(
                            selected = role == r,
                            onClick = { if (isSupported) role = r },
                            enabled = isSupported,
                            label = { Text(r.displayName, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EditorialDeepViolet,
                                selectedLabelColor = EditorialLavender
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalSlot = ApiSlot(
                        provider = selectedProvider,
                        label = label,
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        baseUrl = baseUrl.trim(),
                        role = role,
                        enabled = true
                    )
                    onConfirm(finalSlot)
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EditorialLavender,
                    contentColor = EditorialDeepViolet
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Add Slot", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Cancel", color = EditorialTextSecondary)
            }
        }
    )
}


