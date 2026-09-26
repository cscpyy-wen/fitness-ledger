// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe.protocol

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCipherTest {
    // Synthetic bytes only. Expected ciphertexts were calculated independently with
    // installed PyCryptodome ARC4(drop=1024) and cryptography/OpenSSL ARC4 after
    // consuming 1024 bytes; Python hashlib supplies the SHA-256 and SHA-1 hashes.
    // Neither the upstream Python implementation nor this Kotlin code generated them.
    private val security = "AAECAwQFBgcICQoLDA0ODw==" // bytes 0..15
    private val nonce = "AAECAwQFBgcICQoL" // bytes 0..11
    private val path = "/app/v1/data/band_data"
    private val asciiPayload = "{\"key\":\"weight\",\"limit\":20}"

    @Test
    fun rc4MatchesIndependentKnownAnswerBeforeAndAfter1024Bytes() {
        // Also matches RFC 6229, key 0x0102030405, offsets 0 and 1024.
        val key = byteArrayOf(1, 2, 3, 4, 5)
        CloudCipher.Rc4Stream(key, 0).use {
            assertArrayEquals(hex("b2396305f03dc027ccc3524a0a1118a8"), it.crypt(ByteArray(16)))
        }
        CloudCipher.Rc4Stream(key).use {
            assertArrayEquals(hex("30abbcc7c20b01609f23ee2d5f6bb7df"), it.crypt(ByteArray(16)))
        }
    }

    @Test
    fun rc4MaintainsStateAcrossUpdates() {
        CloudCipher.Rc4Stream(byteArrayOf(1, 2, 3, 4, 5)).use {
            val output = it.crypt(ByteArray(5)) + it.crypt(ByteArray(0)) + it.crypt(ByteArray(11))
            assertArrayEquals(hex("30abbcc7c20b01609f23ee2d5f6bb7df"), output)
        }
    }

    @Test
    fun requestMatchesIndependentWholeProtocolVector() {
        assertEquals(
            linkedMapOf(
                "data" to "Gjcy3tCTFXqORP3YumrBStU0OOk+mXjRINlK",
                "rc4_hash__" to "SAL3axh3cpSkwaeL90gdaICSSyzN4/W7xz63Xw==",
                "signature" to "Z1QY3uC/UkMh88uDd+4xR0pdhQs=",
                "_nonce" to nonce,
            ),
            request(asciiPayload),
        )
    }

    @Test
    fun secondValueDoesNotRestartCipherOrDropAgain() {
        val result = request(asciiPayload)
        assertNotEquals("VEMuwdnUQyDWbP6Lk1WkL8YxaP0z32uydo0DQA==", result["rc4_hash__"])
        assertEquals("SAL3axh3cpSkwaeL90gdaICSSyzN4/W7xz63Xw==", result["rc4_hash__"])
    }

    @Test
    fun unicodeUsesUtf8ByteOffsetsAndStandardBase64() {
        assertEquals(
            linkedMapOf(
                "data" to "Gjc31N3UDWLbxSksO5lulmjA0aZ7zy2Oe45fCXa6Jlo8K5E=",
                "rc4_hash__" to "urqm5dV0MEzDsh0+huS3l9Np7C9rjaeItbdDIQ==",
                "signature" to "g9IwSXLkr+vOuCXcTL91sZtoq+8=",
                "_nonce" to nonce,
            ),
            request("{\"note\":\"体重😀\",\"weight\":72.5}"),
        )
    }

    @Test
    fun explicitEmptyJsonObjectIsStillData() {
        // The API takes serialized JSON, so {} is a payload, never an absent dict.
        assertEquals(
            mapOf(
                "data" to "Gmg=",
                "rc4_hash__" to "avXHnhkPqGzgiJhtpj65EznWJJ8wun+cWjU/vQ==",
                "signature" to "4iAPylBsgVwujCHOk/sgiz53/0Q=",
                "_nonce" to nonce,
            ),
            request("{}"),
        )
    }

    @Test
    fun methodAndLeadingSlashNormalizeBeforeBothSignatures() {
        assertEquals(
            request(asciiPayload),
            CloudCipher.encryptRequest("post", path.removePrefix("/"), security, asciiPayload, nonce),
        )
    }

    @Test
    fun responseMatchesIndependentFreshStreamVector() {
        assertEquals(
            "{\"code\":0,\"data\":{\"weight\":72.5,\"unit\":\"千克\"}}",
            CloudCipher.decryptResponse(
                security, nonce,
                "Gjc61M3UDWLJDbbbs2qCRM0jc/MyhD2DZssNSmauJEQwa4Li+O+FlObXolR++XfUrA==",
            ),
        )
    }

    @Test
    fun malformedResponseUtf8FailsWithoutLeakingInputOrCause() {
        assertSanitizedFailure { CloudCipher.decryptResponse(security, nonce, "nus=") }
    }

    @Test
    fun nonceHasEightRandomBytesAndUnsignedBigEndianEpochMinutes() {
        for (minutes in listOf(0L, 0x01020304L, 0x80000000L, 0xffffffffL)) {
            val decoded = Base64.getDecoder().decode(CloudCipher.nonce(minutes * 60_000 + 59_999))
            assertEquals(12, decoded.size)
            assertEquals(minutes, ByteBuffer.wrap(decoded, 8, 4).int.toLong() and 0xffffffffL)
        }
        val generated = (1..16).map { CloudCipher.nonce(1_700_000_000_000L) }
        assertEquals(16, generated.toSet().size)
    }

    @Test
    fun nonceTimeCannotWrapOrBecomeNegative() {
        assertSanitizedFailure { CloudCipher.nonce(-1) }
        assertSanitizedFailure { CloudCipher.nonce(0x1_0000_0000L * 60_000) }
        assertSanitizedFailure { CloudCipher.nonce(Long.MAX_VALUE) }
    }

    @Test
    fun securityMustBeBoundedCanonicalStandardBase64() {
        val invalid = listOf(
            "", "not a secret", "%%%", "Zg", "Zh==", "_w==", "$security\n",
            Base64.getEncoder().encodeToString(ByteArray(257)),
        )
        invalid.forEach { value ->
            assertSanitizedFailure { CloudCipher.encryptRequest("POST", path, value, "{}", nonce) }
            assertSanitizedFailure { CloudCipher.decryptResponse(value, nonce, "Gmg=") }
        }
    }

    @Test
    fun nonceMustBeExactlyTwelveCanonicalBytes() {
        val invalid = listOf(
            "", "not-a-nonce", "$nonce\n", "$nonce=",
            Base64.getEncoder().encodeToString(ByteArray(11)),
            Base64.getEncoder().encodeToString(ByteArray(13)),
        )
        invalid.forEach { value ->
            assertSanitizedFailure { CloudCipher.encryptRequest("POST", path, security, "{}", value) }
            assertSanitizedFailure { CloudCipher.decryptResponse(security, value, "Gmg=") }
        }
    }

    @Test
    fun responseBase64MustBeNonemptyBoundedAndCanonical() {
        listOf("", "***", "Gmg", "Gmh=", "Gmg=\n", "a".repeat(5_592_409)).forEach { value ->
            assertSanitizedFailure { CloudCipher.decryptResponse(security, nonce, value) }
        }
        // Rounded base64 bounds alone are insufficient: decoded byte count is checked.
        val oversized = Base64.getEncoder().encodeToString(ByteArray(CloudCipher.MAX_RESPONSE_BYTES + 1))
        assertSanitizedFailure { CloudCipher.decryptResponse(security, nonce, oversized) }
    }

    @Test
    fun malformedOrOversizedPayloadIsRejectedBeforeEncryption() {
        listOf("", "\uD800", "a".repeat(CloudCipher.MAX_REQUEST_BYTES + 1), "重".repeat(100_000)).forEach {
            payload -> assertSanitizedFailure { request(payload) }
        }
    }

    @Test
    fun ambiguousMethodOrPathCannotEnterSignature() {
        listOf("", "PO ST", "POST&", "POST\n", "PÖST").forEach {
            method -> assertSanitizedFailure { CloudCipher.encryptRequest(method, path, security, "{}", nonce) }
        }
        listOf("", "/path?x=y", "/path#x", "/path&data=x", "https://example.com/x", "/路径", "/".repeat(2049)).forEach {
            badPath -> assertSanitizedFailure { CloudCipher.encryptRequest("POST", badPath, security, "{}", nonce) }
        }
    }

    @Test
    fun rc4RejectsInvalidConfigurationAndUseAfterClose() {
        assertSanitizedFailure { CloudCipher.Rc4Stream(ByteArray(0)) }
        assertSanitizedFailure { CloudCipher.Rc4Stream(ByteArray(257)) }
        assertSanitizedFailure { CloudCipher.Rc4Stream(byteArrayOf(1), -1) }
        val stream = CloudCipher.Rc4Stream(byteArrayOf(1))
        stream.close()
        assertThrows(IllegalStateException::class.java) { stream.crypt(byteArrayOf(1)) }
    }

    private fun request(payload: String) = CloudCipher.encryptRequest("POST", path, security, payload, nonce)

    private fun assertSanitizedFailure(action: () -> Unit) {
        val exception = assertThrows(IllegalArgumentException::class.java, action)
        assertNull(exception.cause)
        assertTrue(exception.message!!.length < 80)
        assertTrue(!exception.message!!.contains(security))
        assertTrue(!exception.message!!.contains(nonce))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
