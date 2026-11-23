package com.example.ui.editor

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.models.Bubble
import com.example.data.models.UnitState
import com.example.ui.theme.EditorialAmber
import com.example.ui.theme.EditorialCanvas
import com.example.ui.theme.EditorialCrimson
import com.example.ui.theme.EditorialCrimsonLight
import com.example.ui.theme.EditorialDeepViolet
import com.example.ui.theme.EditorialLavender
import com.example.ui.theme.EditorialSuccess
import com.example.ui.theme.EditorialSurface
import com.example.ui.theme.EditorialSurfaceAlt
import com.example.ui.theme.EditorialSurfaceBorder
import com.example.ui.theme.EditorialTextMuted
import com.example.ui.theme.EditorialTextPrimary
import com.example.ui.theme.EditorialTextSecondary
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collectLatest { event ->
            when (event) {
                is EditorEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is EditorEvent.LaunchShareIntent -> context.startActivity(event.intent)
            }
        }
    }

    val page = uiState.page
    val currentIndex = uiState.allPagesInJob.indexOfFirst { it.id == page?.id }
    val totalPages = uiState.allPagesInJob.size

    // Zoom & Pan transforms
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Tap-to-restore action sheet (prompt 20261118, Part 3): a tap selects the
    // unit and opens the sheet; the inline edit card appears only via Edit text.
    var actionSheetBubbleId by remember { mutableStateOf<Int?>(null) }
    var editingBubble by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offset += panChange
        } else {
            offset = Offset.Zero
        }
    }

    Scaffold(
        modifier = Modifier.testTag("editor_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = EditorialLavender,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (page != null) "${page.pageIndex + 1}" else "1",
                                    color = EditorialDeepViolet,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Column {
                            Text(
                                text = if (page != null) "page_${String.format("%03d", page.pageIndex + 1)}.png" else "Editor",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = EditorialTextPrimary
                            )
                            if (totalPages > 1) {
                                Text(
                                    text = "Page ${currentIndex + 1} of $totalPages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EditorialTextMuted
                                )
                            }
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
                    // Page Navigation (Prev/Next)
                    if (totalPages > 1) {
                        IconButton(
                            onClick = {
                                if (currentIndex > 0) {
                                    viewModel.loadPage(uiState.allPagesInJob[currentIndex - 1].id)
                                }
                            },
                            enabled = currentIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Page", tint = if (currentIndex > 0) EditorialTextPrimary else EditorialTextMuted)
                        }
                        IconButton(
                            onClick = {
                                if (currentIndex < totalPages - 1) {
                                    viewModel.loadPage(uiState.allPagesInJob[currentIndex + 1].id)
                                }
                            },
                            enabled = currentIndex < totalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Page", tint = if (currentIndex < totalPages - 1) EditorialTextPrimary else EditorialTextMuted)
                        }
                    }

                    // Export button
                    IconButton(
                        onClick = { viewModel.exportCurrentPage() },
                        enabled = !uiState.isExporting,
                        modifier = Modifier.testTag("export_page_button")
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Export Page PNG", tint = EditorialLavender)
                    }

                    // Share ZIP
                    IconButton(
                        onClick = { viewModel.shareBatchZip() },
                        enabled = !uiState.isExporting
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = "Share ZIP", tint = EditorialTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorialCanvas
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EditorialCanvas)
                    .border(1.dp, EditorialSurfaceBorder.copy(alpha = 0.35f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // View Mode selector (Original / Wiped / Final) + Add Box button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ViewMode.entries.forEach { mode ->
                            FilterChip(
                                selected = uiState.viewMode == mode,
                                onClick = { viewModel.setViewMode(mode) },
                                label = { Text(mode.displayName, fontSize = 12.sp, fontWeight = if (uiState.viewMode == mode) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EditorialLavender,
                                    selectedLabelColor = EditorialDeepViolet
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.addNewBubble() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EditorialDeepViolet,
                            contentColor = EditorialLavender
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Box", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // If a bubble is selected for editing, show edit bar
                val selected = uiState.selectedBubble
                if (selected != null && editingBubble) {
                    Spacer(modifier = Modifier.height(8.dp))
                    BubbleEditInlineCard(
                        bubble = selected,
                        onUpdateText = { viewModel.updateBubbleText(selected.id, it) },
                        onFontSizeChange = { delta -> viewModel.adjustFontSize(selected.id, delta) },
                        onToggleVisibility = { viewModel.toggleBubbleVisibility(selected.id) },
                        onDelete = { viewModel.deleteBubble(selected.id); editingBubble = false },
                        onClose = { viewModel.selectBubble(null); editingBubble = false }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(EditorialCanvas)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformState)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            ) {
                val containerWidth = constraints.maxWidth.toFloat()
                val containerHeight = constraints.maxHeight.toFloat()
                val density = LocalDensity.current

                val imageFile = uiState.currentImageFile
                val (bmpW, bmpH) = remember(page?.width, page?.height, imageFile?.absolutePath, imageFile?.lastModified()) {
                    val pw = page?.width ?: 0
                    val ph = page?.height ?: 0
                    if (pw > 0 && ph > 0) {
                        pw.toFloat() to ph.toFloat()
                    } else if (imageFile != null && imageFile.exists()) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(imageFile.absolutePath, opts)
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            opts.outWidth.toFloat() to opts.outHeight.toFloat()
                        } else {
                            containerWidth to containerHeight
                        }
                    } else {
                        containerWidth to containerHeight
                    }
                }

                val hasValidDims = containerWidth > 0f && containerHeight > 0f && bmpW > 0f && bmpH > 0f
                val fitScale = if (hasValidDims) min(containerWidth / bmpW, containerHeight / bmpH) else 1f
                val fittedW = if (hasValidDims) bmpW * fitScale else containerWidth
                val fittedH = if (hasValidDims) bmpH * fitScale else containerHeight
                val offsetX = if (hasValidDims) (containerWidth - fittedW) / 2f else 0f
                val offsetY = if (hasValidDims) (containerHeight - fittedH) / 2f else 0f

                // Render current image. Cache key carries the file's mtime:
                // re-renders overwrite the same cache path, and Coil would
                // otherwise keep serving the pre-edit bitmap.
                if (imageFile != null && imageFile.exists()) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(imageFile)
                            .memoryCacheKey("${imageFile.absolutePath}:${imageFile.lastModified()}")
                            .diskCacheKey("${imageFile.absolutePath}:${imageFile.lastModified()}")
                            .build(),
                        contentDescription = "Manga Page Canvas",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Draw overlay bounding boxes
                uiState.bubbles.forEach { bubble ->
                    val isSelected = bubble.id == uiState.selectedBubbleId
                    val leftPx = offsetX + bubble.x1 * fittedW
                    val topPx = offsetY + bubble.y1 * fittedH
                    val widthPx = max(16f, bubble.width * fittedW)
                    val heightPx = max(16f, bubble.height * fittedH)
                    val boxWidthDp = with(density) { widthPx.toDp() }
                    val boxHeightDp = with(density) { heightPx.toDp() }
                    val stateColor = when (bubble.unitState) {
                        UnitState.PROCESSED -> EditorialLavender
                        UnitState.SKIPPED -> EditorialAmber
                        UnitState.RESTORED -> EditorialSuccess
                    }
                    val borderColor = when {
                        isSelected -> stateColor
                        bubble.visible -> stateColor.copy(alpha = 0.6f)
                        else -> EditorialCrimsonLight.copy(alpha = 0.5f)
                    }

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                            .size(width = boxWidthDp, height = boxHeightDp)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = borderColor,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .background(
                                color = if (isSelected) stateColor.copy(alpha = 0.2f) else Color.Transparent
                            )
                            .pointerInput(bubble.id, isSelected, fittedW, fittedH) {
                                if (isSelected && fittedW > 0f && fittedH > 0f) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val deltaX = dragAmount.x / fittedW
                                        val deltaY = dragAmount.y / fittedH
                                        val newX1 = (bubble.x1 + deltaX).coerceIn(0f, 1f - bubble.width)
                                        val newY1 = (bubble.y1 + deltaY).coerceIn(0f, 1f - bubble.height)
                                        val newX2 = (newX1 + bubble.width).coerceIn(0f, 1f)
                                        val newY2 = (newY1 + bubble.height).coerceIn(0f, 1f)
                                        viewModel.updateBubbleBox(bubble.id, listOf(newX1, newY1, newX2, newY2))
                                    }
                                }
                            }
                    ) {
                        // Tag Badge positioned outside the box's top-left corner
                        Surface(
                            color = if (isSelected) stateColor else Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (-2).dp, y = (-14).dp)
                        ) {
                            Text(
                                text = "#${bubble.id}",
                                color = if (isSelected) EditorialDeepViolet else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }

                        // Resize handle at bottom right if selected
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(16.dp)
                                    .background(stateColor, CircleShape)
                                    .pointerInput(bubble.id, fittedW, fittedH) {
                                        if (fittedW > 0f && fittedH > 0f) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaX = dragAmount.x / fittedW
                                                val deltaY = dragAmount.y / fittedH
                                                val newX2 = (bubble.x2 + deltaX).coerceIn(bubble.x1 + 0.02f, 1f)
                                                val newY2 = (bubble.y2 + deltaY).coerceIn(bubble.y1 + 0.02f, 1f)
                                                viewModel.updateBubbleBox(bubble.id, listOf(bubble.x1, bubble.y1, newX2, newY2))
                                            }
                                        }
                                    }
                            )
                        }
                    }
                }

                // Tap hit-test layer, topmost in the canvas (Part 3): the
                // bubble overlays above the image swallow taps before the
                // image's own handler ever sees them, so selection lives
                // here. Only taps are consumed; drags fall through to the
                // selected bubble's move/resize handlers below.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.bubbles, offsetX, offsetY, fittedW, fittedH) {
                            detectTapGestures { tapOffset ->
                                val hit = if (fittedW > 0f && fittedH > 0f) {
                                    val relX = (tapOffset.x - offsetX) / fittedW
                                    val relY = (tapOffset.y - offsetY) / fittedH
                                    if (relX in 0f..1f && relY in 0f..1f) {
                                        uiState.bubbles
                                            .filter { b -> relX in b.x1..b.x2 && relY in b.y1..b.y2 }
                                            .minByOrNull { b -> b.width * b.height }
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                                viewModel.selectBubble(hit?.id)
                                editingBubble = false
                                actionSheetBubbleId = hit?.id
                            }
                        }
                )
            }

            // Local re-rendering progress indicator
            if (uiState.isRendering) {
                Surface(
                    color = EditorialSurfaceAlt.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EditorialLavender)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-rendering...", color = EditorialTextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // One action sheet per tapped unit: Restore original (or Re-process when
    // already restored) and Edit text. Restoring re-derives the page from
    // the original with this unit excluded from wipe + typeset.
    actionSheetBubbleId?.let { sheetId ->
        val sheetBubble = uiState.bubbles.find { it.id == sheetId }
        if (sheetBubble != null) {
            ModalBottomSheet(
                onDismissRequest = { actionSheetBubbleId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        text = "Bubble #${sheetBubble.id} — ${sheetBubble.unitState.name.lowercase()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = truncatePreview(sheetBubble.text, "(no OCR text)"),
                        fontSize = 13.sp,
                        color = EditorialTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = truncatePreview(sheetBubble.translated, "(no translation)"),
                        fontSize = 13.sp,
                        color = EditorialTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    when (sheetBubble.unitState) {
                        UnitState.PROCESSED -> {
                            Button(
                                onClick = { viewModel.restoreBubble(sheetBubble.id); actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("restore_original"),
                                colors = ButtonDefaults.buttonColors(containerColor = EditorialLavender, contentColor = EditorialDeepViolet)
                            ) { Text("Restore original") }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { editingBubble = true; actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("edit_text")
                            ) { Text("Edit text") }
                        }
                        UnitState.SKIPPED -> {
                            Text(
                                text = "Left as drawn — not wiped or translated.",
                                fontSize = 13.sp,
                                color = EditorialTextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.reprocessBubble(sheetBubble.id); actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("reprocess_unit"),
                                colors = ButtonDefaults.buttonColors(containerColor = EditorialLavender, contentColor = EditorialDeepViolet)
                            ) { Text("Re-process") }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { editingBubble = true; actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("edit_text")
                            ) { Text("Edit text") }
                        }
                        UnitState.RESTORED -> {
                            Button(
                                onClick = { viewModel.reprocessBubble(sheetBubble.id); actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("reprocess_unit"),
                                colors = ButtonDefaults.buttonColors(containerColor = EditorialLavender, contentColor = EditorialDeepViolet)
                            ) { Text("Re-process") }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { editingBubble = true; actionSheetBubbleId = null },
                                modifier = Modifier.fillMaxWidth().testTag("edit_text")
                            ) { Text("Edit text") }
                        }
                    }
                    TextButton(
                        onClick = { actionSheetBubbleId = null },
                        modifier = Modifier.fillMaxWidth().testTag("unit_sheet_cancel")
                    ) { Text("Cancel") }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun truncatePreview(text: String, blankFallback: String, maxChars: Int = 24): String {
    if (text.isBlank()) return blankFallback
    return if (text.length > maxChars) "${text.take(maxChars)}…" else text
}

@Composable
fun BubbleEditInlineCard(
    bubble: Bubble,
    onUpdateText: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onToggleVisibility: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bubble_edit_card"),
        colors = CardDefaults.cardColors(containerColor = EditorialSurfaceAlt),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: Japanese Source text + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Source Japanese (Bubble #${bubble.id})",
                        style = MaterialTheme.typography.labelSmall,
                        color = EditorialLavender,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = bubble.text.ifBlank { "(No OCR text)" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = EditorialTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = EditorialTextMuted, modifier = Modifier.size(18.dp))
                }
            }

            // Editable translation field
            OutlinedTextField(
                value = bubble.translated,
                onValueChange = onUpdateText,
                label = { Text("English Translation") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bubble_translation_input"),
                singleLine = false,
                maxLines = 3
            )

            // Bottom controls: Font size stepper, visibility toggle, delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Font size stepper
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatSize, contentDescription = null, tint = EditorialTextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${bubble.fontSizeSp.toInt()}sp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EditorialTextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { onFontSizeChange(-2f) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("-", fontSize = 14.sp, color = EditorialLavender)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { onFontSizeChange(2f) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("+", fontSize = 14.sp, color = EditorialLavender)
                    }
                }

                // Actions: Hide / Delete
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (bubble.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Visibility",
                            tint = if (bubble.visible) EditorialSuccess else EditorialTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Bubble",
                            tint = EditorialCrimsonLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

