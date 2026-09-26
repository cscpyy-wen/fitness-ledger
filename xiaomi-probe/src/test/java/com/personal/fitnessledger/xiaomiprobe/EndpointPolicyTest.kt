// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun realAccountClusterAndHealthHostsAreAccepted() {
        listOf("account.xiaomi.com", "c3.account.xiaomi.com", "c3.lp.account.xiaomi.com").forEach {
            assertEquals(it, EndpointPolicy.account("https://$it/path?x=y").host)
        }
        assertEquals("hlth.io.mi.com", EndpointPolicy.health("https://hlth.io.mi.com${EndpointPolicy.WEIGHT_PATH}").host)
    }

    @Test fun suffixTricksCredentialsCleartextPortsAndFragmentsAreRejected() {
        listOf(
            "http://account.xiaomi.com/path", "https://account.xiaomi.com.evil.test/path",
            "https://evilaccount.xiaomi.com/path", "https://account.xiaomi.com@evil.test/path",
            "https://evil.test@account.xiaomi.com/path", "https://account.xiaomi.com:444/path",
            "https://account.xiaomi.com/path#secret", "https://account.xiaomi.com./path",
            "https://account.xiaomi.com\\@evil.test/path", "https://account.xiaomi.com/a\nb",
            "file:///data/data/secret", "javascript:alert(1)", "https://127.0.0.1/",
            "https://mi.com/", "https://xiaomi.com/",
        ).forEach { assertRejected { EndpointPolicy.account(it) } }
    }

    @Test fun dataEndpointCannotBeChangedToWriteOrAnotherHealthCategory() {
        listOf("/app/v1/data/set_fitness_data", "/app/v1/relatives/get_relative_list", "/other").forEach {
            assertRejected { EndpointPolicy.health("https://hlth.io.mi.com$it") }
        }
        assertRejected { EndpointPolicy.health("https://sts-hlth.io.mi.com${EndpointPolicy.WEIGHT_PATH}") }
        assertRejected { EndpointPolicy.health("https://hlth.io.mi.com${EndpointPolicy.WEIGHT_PATH}?key=sleep") }
    }

    @Test fun formEncodingNeverInjectsParametersOrDropsPlusAndEquals() {
        assertEquals("key=weight&data=a%2Bb%3D%26%25+%E4%B8%AD", EndpointPolicy.form(linkedMapOf("key" to "weight", "data" to "a+b=&% 中")))
    }

    @Test fun loginTicketCannotRedirectToArbitraryHealthOrAccountPaths() {
        assertEquals("/healthapp/sts", EndpointPolicy.tokenExchange("https://sts-hlth.io.mi.com/healthapp/sts?ticket=synthetic").path)
        listOf(
            "https://hlth.io.mi.com${EndpointPolicy.WEIGHT_PATH}",
            "https://hlth.io.mi.com/app/v1/relatives/get_relative_list",
            "https://sts-hlth.io.mi.com/other",
            "https://sts-hlth.io.mi.com/healthapp/sts-other",
            "https://sts-hlth.io.mi.com/healthapp/%73ts",
            "https://account.xiaomi.com/synthetic",
        ).forEach { assertRejected { EndpointPolicy.tokenExchange(it) } }
    }

    @Test fun credentialsAndLoginUrlsAreRedactedFromObjectString() {
        assertFalse(LoginChallenge("secret-url", null, 1, "secret-poll", Any()).toString().contains("secret"))
        assertFalse(XiaomiSession("token-secret", "cipher-secret", "uid-secret", "cuid-secret", Any()).toString().contains("secret"))
        assertFalse(HttpReply(200, mapOf("Set-Cookie" to listOf("secret")), "secret".toByteArray()).toString().contains("secret"))
    }

    private fun assertRejected(action: () -> Unit) {
        try { action(); fail("Unsafe URL was accepted") }
        catch (expected: ProbeException) { assertEquals(ProbeProblem.UNSAFE_RESPONSE, expected.problem) }
    }
}
