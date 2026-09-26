// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult

/** All credentials stay in this short-lived process, never in the fitness ledger. */
interface ProbeGateway : AutoCloseable {
    suspend fun beginLogin(): LoginChallenge
    suspend fun awaitLogin(challenge: LoginChallenge): XiaomiSession
    suspend fun readRecentWeights(session: XiaomiSession): WeightReadResult
    override fun close()
}

class LoginChallenge internal constructor(
    val loginUrl: String,
    val qrImage: ByteArray?,
    val expiresAtElapsedMillis: Long,
    internal val pollingUrl: String,
    internal val owner: Any,
) {
    override fun toString(): String = "LoginChallenge(REDACTED)"
}

class XiaomiSession internal constructor(
    internal val serviceToken: String,
    internal val security: String,
    internal val userId: String,
    internal val cUserId: String,
    internal val owner: Any,
) {
    override fun toString(): String = "XiaomiSession(REDACTED)"
}

enum class ProbeProblem(val userMessage: String) {
    NETWORK("暂时无法连接小米服务，请检查网络后重试。"),
    LOGIN_EXPIRED("登录链接已过期，请重新登录。"),
    AUTH_REQUIRED("登录已失效或未完成，请重新登录。"),
    UNSUPPORTED("小米返回了暂不支持的响应，尚未验证这条通路可用。"),
    REJECTED("小米未允许本次查询；不会绕过验证或反复尝试。"),
    UNSAFE_RESPONSE("服务返回了未允许的地址或异常数据，已停止连接。"),
}

class ProbeException(val problem: ProbeProblem) : Exception(problem.userMessage)
