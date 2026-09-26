package com.personal.fitnessledger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.personal.fitnessledger.xiaomiprobe.XiaomiSession
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class XiaomiStoredSession(val generation: String, val accountKey: String, val payload: String, val background: Boolean) {
    override fun toString() = "XiaomiStoredSession(REDACTED)"
}

/** Separate from business backup preferences. Password/passToken are never retained. */
internal class XiaomiSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xiaomi_cloud_credentials", Context.MODE_PRIVATE)
    fun read(): XiaomiStoredSession? = synchronized(lock) {
        val encrypted = prefs.getString("cipher", null) ?: return@synchronized null
        try {
            val savedAt = prefs.getLong("savedAt", 0)
            // Expiry is bounded even if the upstream token happens to remain valid.
            val age = System.currentTimeMillis() - savedAt
            if (age !in 0..30L*86400*1000) { clear(); return@synchronized null }
            val parts = encrypted.split('.',limit=2)
            require(parts.size==2 && encrypted.length<=32768)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(create=false),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            cipher.updateAAD(AAD)
            val bytes = cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP))
            val payload = bytes.toString(Charsets.UTF_8)
            bytes.fill(0)
            val json = JSONObject(payload)
            val user = json.getString("userId")
            require(user.matches(Regex("[0-9]{1,24}")))
            XiaomiStoredSession(prefs.getString("generation",null) ?: error("Missing generation"),fingerprint(user),payload,prefs.getBoolean("background",false))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(session: XiaomiSession, background: Boolean, generation: String = UUID.randomUUID().toString(), isCurrent: () -> Boolean = { true }): String = synchronized(lock) {
        val json = JSONObject().put("serviceToken",session.serviceToken).put("security",session.security)
            .put("userId",session.userId).put("cUserId",session.cUserId)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(create=true)); cipher.updateAAD(AAD)
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        val encrypted=try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        check(isCurrent()) { "连接已取消。" }
        check(prefs.edit().putString("cipher",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+"."+Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("generation",generation).putLong("savedAt",System.currentTimeMillis()).putBoolean("background",background).commit()) { "无法加密保存小米会话，请检查手机存储空间。" }
        generation
    }
    fun clear() = synchronized(lock) {
        check(prefs.edit().clear().putString("generation",UUID.randomUUID().toString()).commit()) { "无法持久化断开状态，请重试。" }
    }
    fun clearIfGeneration(generation: String): Boolean = synchronized(lock) {
        if(prefs.getString("generation",null)!=generation) return@synchronized false
        clear(); true
    }
    fun setBackground(enabled: Boolean) = synchronized(lock) {
        check(prefs.edit().putBoolean("background",enabled).commit()) { "无法保存后台同步设置。" }
    }
    fun <T> whileCurrent(generation: String, block: () -> T): T = synchronized(lock) {
        check(prefs.getString("cipher",null)!=null && prefs.getString("generation",null)==generation) { "小米连接已改变，本次同步未写入。" }
        block()
    }
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS,null) as? SecretKey)?.let { return it }
        check(create) { "Session key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    companion object {
        private val lock=Any()
        private const val KEY_ALIAS="fitness_xiaomi_session_v1"
        private val AAD="fitness-ledger/xiaomi-cn/credentials-v1".toByteArray(Charsets.UTF_8)
        fun fingerprint(userId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("xiaomi-cn:$userId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
