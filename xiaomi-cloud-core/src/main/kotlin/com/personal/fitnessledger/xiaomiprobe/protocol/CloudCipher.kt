// SPDX-License-Identifier: GPL-3.0-or-later
// Mi Fitness wire-protocol reference: Misty02600/mi-fitness-python, crypto.py,
// commit c8aef5910b4a0c50392992c86c28091a995582cc (GPL-3.0-or-later).
// https://github.com/Misty02600/mi-fitness-python/blob/c8aef5910b4a0c50392992c86c28091a995582cc/src/mi_fitness/crypto.py
// Kotlin implementation with strict input bounds and independently checked vectors.
package com.personal.fitnessledger.xiaomiprobe.protocol

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * Legacy transport compatibility for this isolated personal probe only.
 * RC4/SHA-1 are required by the observed service protocol, not a new security design.
 * This layer neither parses JSON nor authenticates decrypted responses: callers must
 * use verified HTTPS and validate the response schema before displaying any data.
 * Inputs/outputs contain secrets or personal data and must never be logged.
 */
object CloudCipher {
    internal const val MAX_REQUEST_BYTES = 256 * 1024
    internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_SECURITY_BYTES = 256
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val methodPattern = Regex("[A-Za-z]{1,16}")
    private val pathPattern = Regex("/[A-Za-z0-9._~/-]*")

    /** Encrypts the exact supplied JSON text, without reserializing it. */
    fun encryptRequest(
        method: String,
        path: String,
        security: String,
        plainJson: String,
        nonce: String = CloudCipher.nonce(),
    ): Map<String, String> {
        require(methodPattern.matches(method)) { "Invalid request method" }
        require(path.isNotEmpty() && path.length <= 2048) { "Invalid request path" }
        val normalizedMethod = method.uppercase(Locale.ROOT)
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        require(pathPattern.matches(normalizedPath)) { "Invalid request path" }
        require(plainJson.isNotEmpty()) { "Invalid request payload" }
        val plaintext = utf8(plainJson, MAX_REQUEST_BYTES)
        try {
            val key = deriveKey(security, nonce)
            try {
                val signedNonce = encoder.encodeToString(key)
                val hash = sign(
                    normalizedMethod, normalizedPath, mapOf("data" to plainJson), signedNonce,
                )
                // TreeMap order is data, rc4_hash__. Only one drop occurs: the
                // second value continues at the first value's UTF-8 byte offset.
                val encrypted = Rc4Stream(key).use { stream ->
                    sortedMapOf(
                        "data" to encoder.encodeToString(stream.crypt(plaintext)),
                        "rc4_hash__" to encoder.encodeToString(stream.crypt(hash.toByteArray(Charsets.UTF_8))),
                    )
                }
                val signature = sign(normalizedMethod, normalizedPath, encrypted, signedNonce)
                return linkedMapOf(
                    "data" to encrypted.getValue("data"),
                    "rc4_hash__" to encrypted.getValue("rc4_hash__"),
                    "signature" to signature,
                    "_nonce" to nonce,
                )
            } finally {
                key.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /** Response encryption begins with its own fresh RC4-drop1024 stream. */
    fun decryptResponse(security: String, nonce: String, ciphertext: String): String {
        val encrypted = decodeBase64(ciphertext, 1, MAX_RESPONSE_BYTES)
        try {
            val key = deriveKey(security, nonce)
            try {
                val plaintext = Rc4Stream(key).use { it.crypt(encrypted) }
                try {
                    return try {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(plaintext)).toString()
                    } catch (_: CharacterCodingException) {
                        throw IllegalArgumentException("Invalid response encoding")
                    }
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                key.fill(0)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    /** Eight random bytes followed by unsigned epoch minutes in big-endian order. */
    fun nonce(nowMillis: Long = System.currentTimeMillis()): String {
        require(nowMillis >= 0) { "Invalid nonce time" }
        val minutes = nowMillis / 60_000L
        require(minutes <= 0xffff_ffffL) { "Invalid nonce time" }
        val randomBytes = ByteArray(8).also(random::nextBytes)
        return encoder.encodeToString(ByteBuffer.allocate(12).put(randomBytes).putInt(minutes.toInt()).array())
    }

    private fun deriveKey(security: String, nonce: String): ByteArray {
        val secret = decodeBase64(security, 1, MAX_SECURITY_BYTES)
        try {
            val nonceBytes = decodeBase64(nonce, 12, 12)
            try {
                return MessageDigest.getInstance("SHA-256").run {
                    update(secret)
                    digest(nonceBytes)
                }
            } finally {
                nonceBytes.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    private fun sign(method: String, path: String, values: Map<String, String>, signedNonce: String): String {
        val message = buildString {
            append(method).append('&').append(path)
            values.toSortedMap().forEach { (key, value) -> append('&').append(key).append('=').append(value) }
            append('&').append(signedNonce)
        }.toByteArray(Charsets.UTF_8)
        try {
            return encoder.encodeToString(MessageDigest.getInstance("SHA-1").digest(message))
        } finally {
            message.fill(0)
        }
    }

    private fun decodeBase64(value: String, minBytes: Int, maxBytes: Int): ByteArray {
        // Reject whitespace, URL-safe spelling, missing padding, and nonzero pad
        // bits by requiring canonical standard base64, with a preallocation cap.
        require(value.isNotEmpty() && value.length <= ((maxBytes + 2) / 3) * 4) {
            "Invalid encoded input"
        }
        val decoded = try {
            decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid encoded input")
        }
        if (decoded.size !in minBytes..maxBytes || encoder.encodeToString(decoded) != value) {
            decoded.fill(0)
            throw IllegalArgumentException("Invalid encoded input")
        }
        return decoded
    }

    private fun utf8(value: String, maxBytes: Int): ByteArray {
        require(value.length <= maxBytes) { "Request payload exceeds limit" }
        val bytes = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid request encoding")
        }
        require(bytes.remaining() <= maxBytes) { "Request payload exceeds limit" }
        return ByteArray(bytes.remaining()).also(bytes::get)
    }

    /** Internal visibility permits independent known-answer tests without networking. */
    internal class Rc4Stream(key: ByteArray, dropBytes: Int = 1024) : AutoCloseable {
        private val state = IntArray(256) { it }
        private var i = 0
        private var j = 0
        private var closed = false

        init {
            require(key.size in 1..256 && dropBytes in 0..4096) { "Invalid cipher input" }
            var swapIndex = 0
            for (index in state.indices) {
                swapIndex = (swapIndex + state[index] + (key[index % key.size].toInt() and 0xff)) and 0xff
                swap(index, swapIndex)
            }
            repeat(dropBytes) { nextByte() }
        }

        fun crypt(input: ByteArray): ByteArray {
            check(!closed) { "Cipher is closed" }
            return ByteArray(input.size) { index -> (input[index].toInt() xor nextByte()).toByte() }
        }

        private fun nextByte(): Int {
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            swap(i, j)
            return state[(state[i] + state[j]) and 0xff]
        }

        private fun swap(a: Int, b: Int) {
            val temporary = state[a]
            state[a] = state[b]
            state[b] = temporary
        }

        override fun close() {
            state.fill(0)
            i = 0
            j = 0
            closed = true
        }
    }
}
