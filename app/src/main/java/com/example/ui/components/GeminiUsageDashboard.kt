package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.telemetry.ApiHealthStatus
import com.example.data.telemetry.ApiUsageMetrics
import com.example.data.telemetry.ApiUsageTracker
import com.example.ui.theme.EditorialAmber
import com.example.ui.theme.EditorialCanvas
import com.example.ui.theme.EditorialCrimson
import com.example.ui.theme.EditorialCrimsonBorder
import com.example.ui.theme.EditorialCrimsonLight
import com.example.ui.theme.EditorialCrimsonText
import com.example.ui.theme.EditorialDeepViolet
import com.example.ui.theme.EditorialLavender
import com.example.ui.theme.EditorialSuccess
import com.example.ui.theme.EditorialSuccessContainer
import com.example.ui.theme.EditorialSurface
import com.example.ui.theme.EditorialSurfaceAlt
import com.example.ui.theme.EditorialSurfaceBorder
import com.example.ui.theme.EditorialTextMuted
import com.example.ui.theme.EditorialTextPrimary
import com.example.ui.theme.EditorialTextSecondary

/**
 * High-craft Gemini API Usage & Rate Limit Telemetry Dashboard.
 *
 * Tracks live rolling requests-per-minute (RPM), capacity utilization,
 * rate limit proximity alerts, and provides suggested wait times & 1-tap pause actions.
 */
@Composable
fun GeminiUsageDashboard(
    modifier: Modifier = Modifier,
    isJobPaused: Boolean = false,
    onPauseJob: (() -> Unit)? = null,
    onResumeJob: (() -> Unit)? = null,
    onManageKeys: (() -> Unit)? = null,
    initiallyExpanded: Boolean = false
) {
    val metrics by ApiUsageTracker.geminiMetrics.collectAsStateWithLifecycle()
    val isAutoThrottle by ApiUsageTracker.autoThrottleEnabled.collectAsStateWithLifecycle()
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    val meterProgress by animateFloatAsState(
        targetValue = metrics.rpmUsagePercent.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "meterProgress"
    )

    val meterColor by animateColorAsState(
        targetValue = when (metrics.status) {
            ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonBorder
            ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
            ApiHealthStatus.HEALTHY -> EditorialLavender
        },
        animationSpec = tween(durationMillis = 300),
        label = "meterColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("gemini_usage_dashboard"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (metrics.status) {
                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimson.copy(alpha = 0.35f)
                ApiHealthStatus.APPROACHING_LIMIT -> EditorialSurfaceAlt
                ApiHealthStatus.HEALTHY -> EditorialSurface
            }
        ),
        border = BorderStroke(
            1.dp,
            when (metrics.status) {
                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonBorder
                ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber.copy(alpha = 0.6f)
                ApiHealthStatus.HEALTHY -> EditorialSurfaceBorder.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Title, Live Status Badge, and Expand Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = when (metrics.status) {
                            ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimson
                            ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber.copy(alpha = 0.2f)
                            ApiHealthStatus.HEALTHY -> EditorialLavender.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (metrics.status) {
                                    ApiHealthStatus.COOLDOWN_ACTIVE -> Icons.Default.HourglassTop
                                    ApiHealthStatus.APPROACHING_LIMIT -> Icons.Default.Speed
                                    ApiHealthStatus.HEALTHY -> Icons.Default.Bolt
                                },
                                contentDescription = null,
                                tint = when (metrics.status) {
                                    ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonLight
                                    ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                    ApiHealthStatus.HEALTHY -> EditorialLavender
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "GEMINI API USAGE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonLight
                                ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                ApiHealthStatus.HEALTHY -> EditorialLavender
                            }
                        )
                        Text(
                            text = metrics.statusHeadline,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = EditorialTextPrimary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status Badge
                    Surface(
                        color = when (metrics.status) {
                            ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimson
                            ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber.copy(alpha = 0.25f)
                            ApiHealthStatus.HEALTHY -> EditorialSuccessContainer.copy(alpha = 0.7f)
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            1.dp,
                            when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonBorder
                                ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                ApiHealthStatus.HEALTHY -> EditorialSuccess.copy(alpha = 0.4f)
                            }
                        )
                    ) {
                        Text(
                            text = when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> "COOLDOWN ${metrics.suggestedWaitSeconds}s"
                                ApiHealthStatus.APPROACHING_LIMIT -> "RATE ALERT"
                                ApiHealthStatus.HEALTHY -> "HEALTHY"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonText
                                ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                ApiHealthStatus.HEALTHY -> EditorialSuccess
                            }
                        )
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = EditorialTextSecondary
                        )
                    }
                }
            }

            // Real-time RPM Capacity Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rolling 60s Pace",
                        fontSize = 11.sp,
                        color = EditorialTextMuted
                    )
                    Text(
                        text = "${metrics.rollingRpm} / ${metrics.rpmLimit} RPM (${(meterProgress * 100).toInt()}%)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = meterColor
                    )
                }

                LinearProgressIndicator(
                    progress = { meterProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = meterColor,
                    trackColor = EditorialSurfaceBorder.copy(alpha = 0.4f)
                )
            }

            // Proactive Alert & Recommendation Box (visible when approaching limit or in cooldown)
            if (metrics.status != ApiHealthStatus.HEALTHY || metrics.suggestedWaitSeconds > 0) {
                Surface(
                    color = when (metrics.status) {
                        ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimson.copy(alpha = 0.5f)
                        ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber.copy(alpha = 0.15f)
                        ApiHealthStatus.HEALTHY -> EditorialSurfaceAlt
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        when (metrics.status) {
                            ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonBorder
                            ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber.copy(alpha = 0.4f)
                            ApiHealthStatus.HEALTHY -> EditorialSurfaceBorder
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> Icons.Default.HourglassBottom
                                ApiHealthStatus.APPROACHING_LIMIT -> Icons.Default.Warning
                                ApiHealthStatus.HEALTHY -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = when (metrics.status) {
                                ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonLight
                                ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                ApiHealthStatus.HEALTHY -> EditorialSuccess
                            },
                            modifier = Modifier.size(18.dp)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = metrics.recommendationMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = when (metrics.status) {
                                    ApiHealthStatus.COOLDOWN_ACTIVE -> EditorialCrimsonText
                                    ApiHealthStatus.APPROACHING_LIMIT -> EditorialAmber
                                    ApiHealthStatus.HEALTHY -> EditorialTextPrimary
                                }
                            )
                        }
                    }
                }
            }

            // One-tap Action Buttons (Pause / Resume / Auto-Throttle / Manage Keys)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onPauseJob != null && onResumeJob != null) {
                    if (isJobPaused) {
                        Button(
                            onClick = onResumeJob,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("dashboard_resume_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EditorialLavender,
                                contentColor = EditorialDeepViolet
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume Batch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPauseJob,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("dashboard_pause_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (metrics.status == ApiHealthStatus.COOLDOWN_ACTIVE) EditorialCrimsonBorder else EditorialSurfaceBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, tint = if (metrics.status == ApiHealthStatus.COOLDOWN_ACTIVE) EditorialCrimsonLight else EditorialTextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Pause Batch",
                                fontSize = 12.sp,
                                color = if (metrics.status == ApiHealthStatus.COOLDOWN_ACTIVE) EditorialCrimsonLight else EditorialTextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (onManageKeys != null) {
                    OutlinedButton(
                        onClick = onManageKeys,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("dashboard_manage_keys_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, EditorialSurfaceBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = EditorialLavender, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Keys", fontSize = 12.sp, color = EditorialLavender)
                    }
                }
            }

            // Expanded Telemetry Breakdown (Daily Requests, Success Rate, Latency, Auto-Throttle Toggle)
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Metric Tiles Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricTile(
                            label = "TODAY TOTAL",
                            value = "${metrics.totalRequestsToday}",
                            caption = "API Calls",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "SUCCESS RATE",
                            value = if (metrics.totalRequestsToday > 0) {
                                "${((metrics.successCount.toFloat() / metrics.totalRequestsToday) * 100).toInt()}%"
                            } else "100%",
                            caption = "${metrics.rateLimitCount} rate limits",
                            valueColor = if (metrics.rateLimitCount > 0) EditorialAmber else EditorialSuccess,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "AVG LATENCY",
                            value = if (metrics.avgLatencyMs > 0) "${metrics.avgLatencyMs}ms" else "—",
                            caption = "Vision + OCR",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Auto-Throttle Configuration Row
                    Surface(
                        color = EditorialCanvas.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Smart Pacing Delay (2.5s)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = EditorialTextPrimary
                                )
                                Text(
                                    text = "Automatically paces page dispatch to stay under 15 RPM",
                                    fontSize = 10.sp,
                                    color = EditorialTextMuted
                                )
                            }

                            Switch(
                                checked = isAutoThrottle,
                                onCheckedChange = { ApiUsageTracker.setAutoThrottle(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = EditorialDeepViolet,
                                    checkedTrackColor = EditorialLavender,
                                    uncheckedThumbColor = EditorialTextMuted,
                                    uncheckedTrackColor = EditorialSurfaceBorder
                                ),
                                modifier = Modifier.testTag("auto_throttle_switch")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = EditorialTextPrimary
) {
    Surface(
        color = EditorialCanvas.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, EditorialSurfaceBorder.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = EditorialTextMuted
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = valueColor
            )
            Text(
                text = caption,
                fontSize = 9.sp,
                color = EditorialTextSecondary
            )
        }
    }
}
