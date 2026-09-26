package com.personal.fitnessledger.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.fitnessledger.data.XiaomiSyncManager
import com.personal.fitnessledger.xiaomiprobe.LoginChallenge
import com.personal.fitnessledger.xiaomiprobe.ProbeException
import com.personal.fitnessledger.xiaomiprobe.XiaomiCloudGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

class XiaomiConnectionViewModel(application: Application): AndroidViewModel(application) {
    val manager=XiaomiSyncManager.get(application)
    var connecting by mutableStateOf(false); private set
    var awaitingLogin by mutableStateOf(false); private set
    var loginError by mutableStateOf<String?>(null); private set
    private var gateway: XiaomiCloudGateway?=null
    private var challenge: LoginChallenge?=null
    private var loginJob: Job?=null
    @Volatile private var attempt: String?=null

    init { foreground() }
    fun foreground() { viewModelScope.launch { try { manager.refresh(); manager.sync(automatic=true) } catch(_: Exception) { /* Main ledger restore screen owns recovery errors. */ } } }
    fun sync(days:Int=7) { viewModelScope.launch { try { manager.sync(days) } catch(_: Exception) { loginError="同步未完成，请稍后重试。" } } }
    fun setBackground(enabled:Boolean) { viewModelScope.launch { try { manager.setBackground(enabled) } catch(_: Exception) { loginError="无法保存后台设置，请稍后重试。" } } }
    fun disconnect() { viewModelScope.launch { try { manager.disconnect() } catch(_: Exception) { loginError="断开状态未能保存，请重试。" } } }

    fun connect() {
        if(connecting) return
        val nonce=UUID.randomUUID().toString()
        attempt=nonce; connecting=true; awaitingLogin=false; loginError=null
        val connection=XiaomiCloudGateway()
        gateway=connection
        loginJob=viewModelScope.launch {
            try {
                val ticket=connection.beginLogin()
                ensureActive()
                if(attempt!=nonce) return@launch
                challenge=ticket; awaitingLogin=true
                val session=connection.awaitLogin(ticket)
                ensureActive()
                if(attempt!=nonce) return@launch
                awaitingLogin=false
                manager.activate(session,nonce) { attempt==nonce }
                ensureActive()
                if(attempt!=nonce) return@launch
                connecting=false; challenge?.qrImage?.fill(0); challenge=null
                attempt=null
                manager.sync()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {
                ensureActive()
                if(attempt==nonce) {
                    loginError=if(failure is ProbeException) failure.problem.userMessage else "连接未完成，请使用原小米账号重试；已有历史不受影响。"
                    connecting=false; awaitingLogin=false
                    manager.cancelActivation(nonce)
                    attempt=null
                }
            } finally {
                connection.close()
                if(gateway===connection) { gateway=null; challenge=null }
            }
        }
    }
    fun openLogin(context: Context) {
        val ticket=challenge ?: return
        try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ticket.loginUrl)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch(_: Exception) { loginError="无法打开浏览器，请检查手机默认浏览器设置。" }
    }
    fun cancelLogin() {
        val old=attempt
        attempt=null; connecting=false; awaitingLogin=false
        loginJob?.cancel(); gateway?.close(); gateway=null
        challenge?.qrImage?.fill(0); challenge=null
        if(old!=null) viewModelScope.launch { manager.cancelActivation(old) }
    }
    override fun onCleared() {
        val old=attempt
        attempt=null; loginJob?.cancel(); gateway?.close(); challenge?.qrImage?.fill(0)
        // An incomplete activation is invalidated before it can persist; completed sessions intentionally survive.
        if(old!=null) runCatching { com.personal.fitnessledger.data.XiaomiSessionStore(getApplication()).clearIfGeneration(old) }
        super.onCleared()
    }
}
