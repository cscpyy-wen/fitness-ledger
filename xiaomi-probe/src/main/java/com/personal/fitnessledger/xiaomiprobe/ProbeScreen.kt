// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ProbeStage { INTRO, CONNECTING, AWAITING_LOGIN, AUTHENTICATED, READING, RESULTS }

/** Screen data only; account tokens and login URLs never enter Compose state. */
data class ProbeUiState(
    val stage: ProbeStage = ProbeStage.INTRO,
    val consentAccepted: Boolean = false,
    val qrImage: ImageBitmap? = null,
    val records: List<WeightRecord> = emptyList(),
    val rejectedCount: Int = 0,
    val incomplete: Boolean = false,
    val errorMessage: String? = null,
)

@Composable
fun ProbeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFA4D3B4),
            onPrimary = Color(0xFF143B27),
            primaryContainer = Color(0xFF243B2C),
            onPrimaryContainer = Color(0xFFCBE5D2),
            background = Color(0xFF111813),
            onBackground = Color(0xFFEAF0E8),
            surface = Color(0xFF1C261F),
            onSurface = Color(0xFFEAF0E8),
            surfaceVariant = Color(0xFF28332A),
            onSurfaceVariant = Color(0xFFB8C5B9),
            outlineVariant = Color(0xFF344238),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF285C44),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE8F0E3),
            onPrimaryContainer = Color(0xFF35513C),
            background = Color(0xFFF6F7F2),
            onBackground = Color(0xFF1E3024),
            surface = Color.White,
            onSurface = Color(0xFF1E3024),
            surfaceVariant = Color(0xFFEDF1E9),
            onSurfaceVariant = Color(0xFF5C6B60),
            outlineVariant = Color(0xFFDFE6DC),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun ProbeScreen(
    state: ProbeUiState,
    onConsentChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onOpenLogin: () -> Unit,
    onReadWeights: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            ProbeActions(state, onConnect, onOpenLogin, onReadWeights, onDisconnect)
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets).testTag("probe-content"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(8.dp).background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "小米体重 · 独立验证",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = when (state.stage) {
                        ProbeStage.INTRO -> "看看小米云端\n能读到什么。"
                        ProbeStage.CONNECTING -> "准备一次\n只读连接。"
                        ProbeStage.AWAITING_LOGIN -> "在小米页面\n完成登录。"
                        ProbeStage.AUTHENTICATED -> "账号已连接。\n接下来，读取记录。"
                        ProbeStage.READING -> "正在读取\n近 7 天记录。"
                        ProbeStage.RESULTS -> if (state.records.isEmpty()) "暂未读到\n可用记录。" else "读到了 ${state.records.size} 条\n待核对的记录。"
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 31.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.7).sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "非官方通路验证 · 不写入正式账本",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }

            if (state.errorMessage != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().testTag("probe-error")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            state.errorMessage,
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }

            when (state.stage) {
                ProbeStage.INTRO -> {
                    item {
                        ProbeCard {
                            ScopeRow("01", "只读近 7 天", "查询账号云端已有的体重数据，可能没有记录。")
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            ScopeRow("02", "先核对，再判断", "读到记录不代表已验证 S400 来源，需与你的小米报告逐项核对。")
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            ScopeRow("03", "仅在本次会话中", "凭据及结果仅存于进程内存；退出、断开或重建页面即清除。")
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "连接前，请了解账号权限",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("consent")
                                    .toggleable(
                                        value = state.consentAccepted,
                                        role = Role.Checkbox,
                                        onValueChange = onConsentChanged,
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Checkbox(checked = state.consentAccepted, onCheckedChange = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "我理解并同意使用非官方通路：工具将取得小米账号会话权限，可能具有超出本次体重读取的访问能力。这并非官方限权 OAuth 授权。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    lineHeight = 21.sp,
                                )
                            }
                            Text(
                                "密码、验证码只在小米登录页面输入；本工具不接收这些信息。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }

                ProbeStage.CONNECTING -> item {
                    ProgressCard("正在请求登录入口", "连接仅用于本次验证，可随时取消。")
                }

                ProbeStage.AWAITING_LOGIN -> {
                    item {
                        ProbeCard {
                            Text("等待小米确认登录", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Spacer(Modifier.height(10.dp))
                            MutedText("点击下方按钮，在系统浏览器中完成登录，然后返回这里。登录链接短时有效，过期后会停止连接。")
                            if (state.qrImage != null) {
                                Spacer(Modifier.height(22.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = state.qrImage,
                                        contentDescription = "小米登录二维码，可使用另一台设备扫描",
                                        modifier = Modifier.size(188.dp).background(Color.White)
                                            .padding(10.dp).testTag("login-qr"),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "也可使用另一台设备扫描上方二维码。\n二维码仅在当前页面展示，请勿转发。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                    item { SessionBoundary() }
                }

                ProbeStage.AUTHENTICATED -> {
                    item {
                        ProbeCard {
                            Text("登录已确认", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            MutedText("尚未读取体重。点击下方按钮，查询当前账号近 7 天的云端记录。")
                            Spacer(Modifier.height(18.dp))
                            Text(
                                "可能没有记录，也可能缺少体脂或水分。",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                    item { SessionBoundary() }
                }

                ProbeStage.READING -> {
                    item { ProgressCard("正在查询云端", "正在请求近 7 天记录。返回的数据仍需人工核对。") }
                    item { SessionBoundary() }
                }

                ProbeStage.RESULTS -> {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "待与你的小米报告核对，不代表已验证 S400 来源。",
                                modifier = Modifier.padding(18.dp).testTag("verification-boundary"),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 14.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (state.incomplete || state.rejectedCount > 0) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.incomplete) {
                                    MutedText("本次返回可能不完整，不能据此判断账号没有其他记录。", "incomplete-notice")
                                }
                                if (state.rejectedCount > 0) {
                                    MutedText("${state.rejectedCount} 条异常或无法解析的数据已跳过。", "rejected-notice")
                                }
                            }
                        }
                    }
                    if (state.records.isEmpty()) {
                        item {
                            ProbeCard {
                                Text("没有可展示的体重记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                MutedText("可能是近 7 天没有数据、当前账号或数据区域不同，或这条非官方通路不支持相关设备。此结果不代表你的小米报告为空。")
                            }
                        }
                    } else {
                        item {
                            MutedText("时间按手机时区显示 · “—” 表示缺失\n体脂与水分为云端返回值，未作推算。")
                        }
                        itemsIndexed(state.records) { index, record -> WeightCard(record, index) }
                    }
                    item { SessionBoundary() }
                }
            }
        }
    }
}

@Composable
private fun ProbeActions(
    state: ProbeUiState,
    onConnect: () -> Unit,
    onOpenLogin: () -> Unit,
    onReadWeights: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.stage) {
                ProbeStage.INTRO -> {
                    MainButton("连接小米账号", "connect", state.consentAccepted, onConnect)
                    Spacer(Modifier.height(8.dp))
                    Text("仅用于验证 · 不会写入正式账本", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                ProbeStage.AWAITING_LOGIN -> MainButton("在小米页面登录", "open-login", onClick = onOpenLogin)
                ProbeStage.AUTHENTICATED -> MainButton("读取近7天记录", "read-weights", onClick = onReadWeights)
                ProbeStage.RESULTS -> MainButton("断开并清除", "disconnect", onClick = onDisconnect)
                ProbeStage.CONNECTING, ProbeStage.READING -> Unit
            }
            if (state.stage != ProbeStage.INTRO && state.stage != ProbeStage.RESULTS) {
                TextButton(onClick = onDisconnect, modifier = Modifier.testTag("disconnect")) {
                    Text(if (state.stage == ProbeStage.AUTHENTICATED) "断开并清除" else "取消并清除")
                }
            }
        }
    }
}

@Composable
private fun MainButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 17.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProbeCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) { content() }
    }
}

@Composable
private fun ScopeRow(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(number, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressCard(title: String, description: String) {
    ProbeCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        MutedText(description)
    }
}

@Composable
private fun MutedText(text: String, tag: String? = null) {
    Text(
        text,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 22.sp,
    )
}

@Composable
private fun SessionBoundary() {
    MutedText("凭据及结果仅存于进程内存。退出或断开即清除；不会保存到正式账本。")
}

@Composable
private fun WeightCard(record: WeightRecord, index: Int) {
    val date = DateTimeFormatter.ofPattern("M月d日  HH:mm", Locale.SIMPLIFIED_CHINESE)
        .withZone(ZoneId.systemDefault()).format(record.measuredAt)
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("weight-record-$index"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatNumber(record.weightKg), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
                Text(" kg", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                Metric("体脂", record.bodyFatPercent, Modifier.weight(1f))
                Metric("水分", record.waterPercent, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, percent: Double?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(percent?.let { "${formatNumber(it)}%" } ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// Do not round a cloud value such as 70.25 to 70.3 when asking the user to verify it.
private fun formatNumber(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
