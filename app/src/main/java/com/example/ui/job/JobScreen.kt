package com.example.ui.job

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ui.components.GeminiUsageDashboard
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
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
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ForgeCyan
import com.example.ui.theme.ForgeGreen
import com.example.ui.theme.ForgeRed
import com.example.ui.theme.ForgeViolet
import kotlinx.coroutines.flow.collectLatest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobScreen(
    viewModel: JobViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (Long) -> Unit,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collectLatest { event ->
            when (event) {
                is JobEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is JobEvent.LaunchShareIntent -> context.startActivity(event.intent)
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("job_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = EditorialLavender,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "#${uiState.job?.id ?: "1"}",
                                    color = EditorialDeepViolet,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Batch Translation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EditorialTextPrimary
                            )
                            Text(
                                text = uiState.summaryText,
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = EditorialTextPrimary
                        )
                    }
                },
                actions = {
                    // Pause / Resume Button
                    if (uiState.isPaused) {
                        OutlinedButton(
                            onClick = { viewModel.resumeJob() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("resume_job_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = EditorialLavender, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", fontSize = 12.sp, color = EditorialLavender, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.pauseJob() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("pause_job_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, tint = EditorialTextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", fontSize = 12.sp, color = EditorialTextSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorialCanvas
                )
            )
        },
        bottomBar = {
            if (uiState.doneCount > 0) {
                Surface(
                    color = EditorialCanvas,
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { viewModel.exportAllPages() },
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("export_all_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EditorialLavender,
                                contentColor = EditorialDeepViolet
                            )
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export Done (${uiState.doneCount})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.shareBatchZip() },
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("share_zip_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EditorialLavender)
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = null, tint = EditorialLavender, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ZIP Batch", fontSize = 13.sp, color = EditorialLavender, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val total = uiState.pages.size
            val runningCount = uiState.pages.count { it.status.isInProgress }
            val failedCount = uiState.pages.count { it.status == PageStatus.FAILED }
            val progressPercent = if (total > 0) ((uiState.doneCount.toFloat() / total) * 100).toInt() else 0

            // Editorial Batch Progress Card (matching Design HTML mockup)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = EditorialSurfaceAlt
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BATCH PROGRESS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = EditorialLavender,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${uiState.doneCount}",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = EditorialTextPrimary
                            )
                            Text(
                                text = "done",
                                fontSize = 12.sp,
                                color = EditorialTextSecondary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            Text(
                                text = "|",
                                fontSize = 14.sp,
                                color = EditorialTextMuted.copy(alpha = 0.4f),
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
                            )
                            Text(
                                text = "$runningCount",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = EditorialLavender
                            )
                            Text(
                                text = "running",
                                fontSize = 12.sp,
                                color = EditorialTextSecondary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$progressPercent%",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = EditorialLavender
                        )
                        if (failedCount > 0) {
                            Text(
                                text = "$failedCount Failed",
                                fontSize = 11.sp,
                                color = EditorialCrimsonLight,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = "of $total total",
                                fontSize = 11.sp,
                                color = EditorialTextMuted
                            )
                        }
                    }
                }
            }

            // Gemini API Usage & Rate Limit Telemetry Dashboard
            GeminiUsageDashboard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                isJobPaused = uiState.isPaused,
                onPauseJob = { viewModel.pauseJob() },
                onResumeJob = { viewModel.resumeJob() },
                onManageKeys = onNavigateToSettings
            )

            // Dynamic Waiting / Rate Limit Banner (matching Design HTML mockup)
            AnimatedVisibility(visible = uiState.waitingBanner != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = EditorialCrimson
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EditorialCrimsonBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = EditorialCrimsonText,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.waitingBanner ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialCrimsonText,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // Page Queue List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(
                    items = uiState.pages,
                    key = { it.id }
                ) { page ->
                    PageQueueCard(
                        page = page,
                        onClick = {
                            if (page.status == PageStatus.DONE || page.wipedImagePath.isNotBlank()) {
                                onNavigateToEditor(page.id)
                            }
                        },
                        onRetry = { viewModel.retryPage(page) }
                    )
                }
            }
        }
    }
}

@Composable
fun PageQueueCard(
    page: PageEntity,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    val isClickable = page.status == PageStatus.DONE || page.wipedImagePath.isNotBlank()
    val previewFile = if (page.finalImagePath.isNotBlank() && File(page.finalImagePath).exists()) {
        File(page.finalImagePath)
    } else if (page.originalCachePath.isNotBlank() && File(page.originalCachePath).exists()) {
        File(page.originalCachePath)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isClickable, onClick = onClick)
            .testTag("page_card_${page.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (page.status == PageStatus.FAILED)
                EditorialSurface.copy(alpha = 0.6f)
            else
                EditorialSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (page.status) {
                PageStatus.FAILED -> EditorialCrimson.copy(alpha = 0.4f)
                PageStatus.DONE -> EditorialLavender.copy(alpha = 0.3f)
                else -> EditorialSurfaceBorder.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Thumbnail preview box
            Box(
                modifier = Modifier
                    .size(56.dp, 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EditorialCanvas)
                    .border(
                        1.dp,
                        if (page.status == PageStatus.FAILED) EditorialCrimsonBorder else EditorialSurfaceBorder.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (previewFile != null) {
                    AsyncImage(
                        model = previewFile,
                        contentDescription = "Page #${page.pageIndex + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (page.status == PageStatus.FAILED) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Failed",
                        tint = EditorialCrimsonLight,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = "IMG_${page.pageIndex + 1}",
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = EditorialLavender
                    )
                }
            }

            // Details Column
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "page_${String.format("%03d", page.pageIndex + 1)}.png",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (page.status == PageStatus.FAILED) EditorialCrimsonLight else EditorialTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Status Pill (matching Editorial design)
                    when (page.status) {
                        PageStatus.FAILED -> {
                            OutlinedButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EditorialLavender)
                            ) {
                                Text("Retry", fontSize = 10.sp, color = EditorialLavender, fontWeight = FontWeight.Bold)
                            }
                        }
                        PageStatus.DONE -> {
                            Surface(
                                color = EditorialDeepViolet,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Done",
                                    color = EditorialLavender,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        PageStatus.QUEUED -> {
                            Surface(
                                color = EditorialSurfaceBorder,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Queued",
                                    color = EditorialTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        else -> {
                            Surface(
                                color = EditorialDeepViolet,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "${page.status.displayName}...",
                                    color = EditorialLavender,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Active slot / details line
                if (page.status == PageStatus.FAILED && page.errorMessage.isNotBlank()) {
                    Text(
                        text = "Failed: ${page.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EditorialCrimsonLight,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (page.status == PageStatus.WAITING && page.remainingWaitSeconds() > 0) {
                    Text(
                        text = "Waiting ${page.remainingWaitSeconds()}s for slot: ${page.activeSlotName.ifBlank { "Next" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EditorialLavender,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                } else if (page.activeSlotName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Slot: ${page.activeSlotName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EditorialTextMuted,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        if (page.status == PageStatus.DONE) {
                            Text(
                                text = "· ${page.getBubbles().size} bubbles",
                                style = MaterialTheme.typography.bodySmall,
                                color = EditorialTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
