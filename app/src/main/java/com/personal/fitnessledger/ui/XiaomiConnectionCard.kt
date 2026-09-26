package com.personal.fitnessledger.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.XiaomiSyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun XiaomiConnectionCard(state:XiaomiSyncState, connecting:Boolean, awaitingLogin:Boolean, loginError:String?,
    onConnect:()->Unit,onLogin:()->Unit,onCancelLogin:()->Unit,onSync:(Int)->Unit,onBackground:(Boolean)->Unit,onDisconnect:()->Unit) {
    var consent by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val context=LocalContext.current
    DisposableEffect(connecting) {
        val window=(context as? Activity)?.window
        val wasSecure=((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE)!=0
        if(connecting) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(connecting && !wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Card(shape=RoundedCornerShape(22.dp), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow), modifier=Modifier.fillMaxWidth().testTag("xiaomi-sync-card")) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("小米运动健康",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text(if(state.connected) (if(state.background) "已连接 · 后台定时同步" else "已连接 · 仅前台同步") else "连接账号，让称重记录回到身体趋势",style=MaterialTheme.typography.bodyMedium)
            state.lastSyncMillis?.let { Text("最近查询："+DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            (loginError ?: state.error)?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            state.message?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(state.partial) Text("最近返回可能不完整；已保存记录仍可使用。",style=MaterialTheme.typography.bodySmall)
            when {
                connecting -> {
                    Text(if(awaitingLogin) "请在小米官方页面登录，再返回这里。" else "正在连接小米…")
                    if(awaitingLogin) Button(onClick=onLogin,modifier=Modifier.fillMaxWidth().testTag("xiaomi-open-login")) { Text("在小米页面登录") }
                    TextButton(onClick=onCancelLogin) { Text("取消连接") }
                }
                state.connected -> {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Button(onClick={onSync(7)},enabled=!state.syncing,modifier=Modifier.weight(1f).testTag("xiaomi-sync-now")) { Text(if(state.syncing) "正在同步…" else "立即同步") }
                        TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起设置" else "同步设置") }
                    }
                    if(expanded) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("后台自动同步")
                            Text("约每 2 小时检查；系统可能延后",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked=state.background,onCheckedChange=onBackground,modifier=Modifier.testTag("xiaomi-background"))
                    }
                    Text("打开 App 也会补查近 7 天。称重后请让小米端先完成同步；这里不直接连接秤。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onSync(56)},enabled=!state.syncing) { Text("补齐近 8 周") }
                        TextButton(onClick={confirmDisconnect=true}) { Text("断开连接") }
                    }
                    }
                }
                else -> OutlinedButton(onClick={consent=true},modifier=Modifier.fillMaxWidth().testTag("xiaomi-connect")) { Text("连接并自动同步") }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("连接你的小米账号")},text={Text("使用已验证的非官方云端通路，并非仅限体重的官方 OAuth 授权，会话可能具有更广权限。\n\n密码、验证码仅在小米官方页面输入。为自动同步，会话由手机 Keystore 加密保存，不进入备份、不发送给照片识别服务。连接后自动保存近 7 天原值；可补齐 8 周。\n\n后台受澎湃 OS 限制，不保证称重后立即到账。可随时断开，保留已有历史。")},confirmButton={TextButton(onClick={consent=false;onConnect()},modifier=Modifier.testTag("xiaomi-consent-confirm")){Text("同意并连接")}},dismissButton={TextButton(onClick={consent=false}){Text("暂不连接")}})
    if(confirmDisconnect) AlertDialog(onDismissRequest={confirmDisconnect=false},title={Text("断开小米连接？")},text={Text("清除本机加密会话并停止后台同步，已保存的体重、体脂和水分仍保留。不注销浏览器中的小米账号。")},confirmButton={TextButton(onClick={confirmDisconnect=false;onDisconnect()}){Text("断开")}},dismissButton={TextButton(onClick={confirmDisconnect=false}){Text("保留连接")}})
}
