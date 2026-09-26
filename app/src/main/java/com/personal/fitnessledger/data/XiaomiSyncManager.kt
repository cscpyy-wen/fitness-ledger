package com.personal.fitnessledger.data

import android.content.Context
import com.personal.fitnessledger.xiaomiprobe.ProbeException
import com.personal.fitnessledger.xiaomiprobe.ProbeProblem
import com.personal.fitnessledger.xiaomiprobe.XiaomiCloudGateway
import com.personal.fitnessledger.xiaomiprobe.XiaomiSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class XiaomiSyncState(
    val connected: Boolean = false,
    val background: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncMillis: Long? = null,
    val partial: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val revision: Long = 0,
)

/** One process, one writer. Network never holds the whole-ledger restore lock. */
class XiaomiSyncManager internal constructor(context: Context) {
    private val context=context.applicationContext
    private val store=XiaomiSessionStore(this.context)
    private val mutex=Mutex()
    private val mutableState=MutableStateFlow(XiaomiSyncState())
    val state=mutableState.asStateFlow()
    @Volatile private var activeGateway: XiaomiCloudGateway?=null
    @Volatile private var lastAttempt=0L

    suspend fun refresh() = withContext(Dispatchers.IO) {
        LedgerBackupManager.withLedgerReadyAccess(context) {
            val session=store.read()
            val ledger=FitnessRepository(context).use { it.xiaomiLedgerStatus() }
            mutableState.update { it.copy(connected=session!=null,background=session?.background==true,lastSyncMillis=ledger.lastSyncMillis,partial=ledger.partial) }
            if(session==null || !session.background) XiaomiSyncScheduler.cancel(context)
            else if(!XiaomiSyncScheduler.schedule(context)) mutableState.update { it.copy(error="系统暂未接受后台任务；仍可点击立即同步。") }
        }
    }

    suspend fun activate(session: XiaomiSession, attempt: String, isCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        LedgerBackupManager.withLedgerReadyAccess(context) {
            check(isCurrent()) { "连接已取消。" }
            val bound=FitnessRepository(context).use { it.xiaomiLedgerStatus().accountKey }
            check(bound==null || bound==XiaomiSessionStore.fingerprint(session.userId)) { "当前账本属于另一个小米账号，请使用原账号连接。" }
            store.save(session,background=true,generation=attempt,isCurrent=isCurrent)
            mutableState.update { it.copy(connected=true,background=true,error=null,message="连接已保存，正在同步近 7 天。") }
            if(!XiaomiSyncScheduler.schedule(context)) mutableState.update { it.copy(error="后台任务未获系统接受，请使用立即同步。") }
        }
    }

    suspend fun cancelActivation(attempt: String) = withContext(Dispatchers.IO) {
        LedgerBackupManager.withLedgerReadyAccess(context) {
            if(store.clearIfGeneration(attempt)) XiaomiSyncScheduler.cancel(context)
        }
        refresh()
    }

    suspend fun setBackground(enabled: Boolean) = withContext(Dispatchers.IO) {
        LedgerBackupManager.withLedgerReadyAccess(context) {
            check(store.read()!=null) { "请先连接小米账号。" }
            store.setBackground(enabled)
            if(enabled) {
                if(!XiaomiSyncScheduler.schedule(context)) { store.setBackground(false); error("系统未接受后台任务，请稍后重试。") }
            } else XiaomiSyncScheduler.cancel(context)
            mutableState.update { it.copy(background=enabled,error=null) }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        // Clear durable authorization before closing an in-flight request; a late result cannot commit.
        LedgerBackupManager.withLedgerReadyAccess(context) { store.clear() }
        XiaomiSyncScheduler.cancel(context)
        activeGateway?.close()
        mutableState.update { it.copy(connected=false,background=false,syncing=false,error=null,message="已断开；已保存的称重记录仍保留。") }
    }

    suspend fun sync(days: Int=7, backgroundJob: Boolean=false, automatic: Boolean=false) = withContext(Dispatchers.IO) {
        require(days in 1..56)
        if(!mutex.tryLock()) return@withContext
        var gateway: XiaomiCloudGateway?=null
        var generation: String?=null
        try {
            if(automatic && System.currentTimeMillis()-lastAttempt in 0..15*60*1000L) return@withContext
            val restoredGeneration=FitnessDatabase.currentRestoreGeneration()
            val credentials=LedgerBackupManager.withLedgerReadyAccess(context) { store.read() } ?: run { refresh(); return@withContext }
            if(backgroundJob && !credentials.background) return@withContext
            generation=credentials.generation
            lastAttempt=System.currentTimeMillis()
            mutableState.update { it.copy(syncing=true,error=null,message=if(days>7) "正在补齐近 8 周，请稍候…" else "正在同步近 7 天…") }
            gateway=gatewayFactory()
            activeGateway=gateway
            val session=gateway.resumeSession(credentials.payload)
            val result=gateway.readWeightWindow(session,days)
            currentCoroutineContext().ensureActive()
            val imported=LedgerBackupManager.withLedgerReadyAccess(context) {
                check(restoredGeneration==FitnessDatabase.currentRestoreGeneration()) { "账本已恢复，本次旧同步未写入，请重新连接。" }
                store.whileCurrent(credentials.generation) {
                    if(backgroundJob) check(store.read()?.background==true) { "后台同步已关闭。" }
                    FitnessRepository(context).use { it.importXiaomiWeights(credentials.accountKey,result,System.currentTimeMillis()) }
                }
            }
            val report=when {
                imported.partial -> "已保存可用记录；返回不完整或有 ${imported.rejected} 条异常，不能视为完整历史。"
                result.records.isEmpty() -> "本次未返回称重记录；已有历史未删除。请确认小米端已同步。"
                else -> "新增 ${imported.inserted} 条 · 更新 ${imported.updated} 条 · 已有 ${imported.unchanged} 条"
            }
            mutableState.update { it.copy(message=report,error=null,revision=it.revision+1) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) {
            currentCoroutineContext().ensureActive()
            if(failure is ProbeException && failure.problem==ProbeProblem.AUTH_REQUIRED) {
                if(generation!=null) runCatching { LedgerBackupManager.withLedgerReadyAccess(context) { store.whileCurrent(generation) { store.clear() } } }
                XiaomiSyncScheduler.cancel(context)
            } else if(failure is ProbeException && failure.problem in setOf(ProbeProblem.REJECTED,ProbeProblem.UNSUPPORTED,ProbeProblem.UNSAFE_RESPONSE)) {
                if(generation!=null) runCatching { LedgerBackupManager.withLedgerReadyAccess(context) { store.whileCurrent(generation) { store.setBackground(false) } } }
                XiaomiSyncScheduler.cancel(context)
            }
            mutableState.update { it.copy(error=when(failure) {
                is ProbeException -> if(failure.problem==ProbeProblem.AUTH_REQUIRED) "小米登录已过期，请重新连接。历史记录不受影响。" else failure.problem.userMessage
                is IllegalStateException -> failure.message?.takeIf { text -> text.length<150 } ?: "同步未完成，已有记录保留。"
                else -> "同步未完成，请检查网络或重新连接；已有记录保留。"
            }, message=null) }
        } finally {
            gateway?.close()
            if(activeGateway===gateway) activeGateway=null
            mutableState.update { it.copy(syncing=false) }
            mutex.unlock()
        }
        refresh()
    }

    companion object {
        @Volatile private var instance: XiaomiSyncManager?=null
        internal var gatewayFactory: () -> XiaomiCloudGateway = { XiaomiCloudGateway() }
        fun get(context: Context): XiaomiSyncManager = instance ?: synchronized(this) { instance ?: XiaomiSyncManager(context).also { instance=it } }
    }
}
