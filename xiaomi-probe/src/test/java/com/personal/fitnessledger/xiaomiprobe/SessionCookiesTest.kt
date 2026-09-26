// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import java.net.URI
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookiesTest {
    private var now = Instant.parse("2026-09-21T00:00:00Z").toEpochMilli()
    private val cookies = SessionCookies { now }
    private val account = URI("https://account.xiaomi.com/longPolling/loginUrl")
    private val sts = URI("https://sts-hlth.io.mi.com/healthapp/sts?ticket=synthetic")
    private val health = URI("https://hlth.io.mi.com/app/v1/data/get_fitness_data_by_time")

    @Test
    fun hostOnlyCookieDoesNotReachSubdomainsOrSiblingHosts() {
        cookies.receive(account, listOf("userId=123; Path=/"))
        assertEquals("userId=123", cookies.header(account))
        assertEquals("", cookies.header(URI("https://c3.account.xiaomi.com/")))
        assertEquals("", cookies.header(sts))
        assertEquals("", cookies.header(health))
    }

    @Test
    fun explicitDomainWithoutLeadingDotReachesNestedAccountHosts() {
        cookies.receive(account, listOf("cUserId=abc; Domain=xiaomi.com; Path=/"))
        assertEquals("cUserId=abc", cookies.header(URI("https://c3.lp.account.xiaomi.com/poll")))
        assertEquals("abc", cookies.accountValue("cUserId"))
    }

    @Test
    fun leadingDotAndCaseDoNotChangeExplicitDomainSemantics() {
        cookies.receive(sts, listOf("serviceToken=first; Domain=.IO.MI.COM; Path=/"))
        assertEquals("serviceToken=first", cookies.header(health))
        cookies.receive(sts, listOf("serviceToken=second; Domain=io.mi.com; Path=/"))
        assertEquals("serviceToken=second", cookies.header(health))
        assertEquals("second", cookies.healthServiceToken())
    }

    @Test
    fun exactStsIssuerCanProvideHostOnlyHealthTokenWithoutCookieScopeExpansion() {
        cookies.receive(sts, listOf("serviceToken=sts-token"))
        assertEquals("sts-token", cookies.healthServiceToken())
        assertEquals("serviceToken=sts-token", cookies.header(sts))
        assertEquals("", cookies.header(health))
        assertEquals("", cookies.accountValue("serviceToken"))
    }

    @Test
    fun accountAndHealthTokensCannotMasqueradeAsStsCredential() {
        cookies.receive(account, listOf("serviceToken=account-token; Domain=.xiaomi.com; Path=/"))
        cookies.receive(health, listOf("serviceToken=health-token; Domain=.io.mi.com; Path=/"))
        assertEquals("", cookies.healthServiceToken())
        assertEquals("account-token", cookies.accountValue("serviceToken"))
        cookies.receive(sts, listOf("serviceToken=actual-sts-token; Path=/healthapp"))
        assertEquals("actual-sts-token", cookies.healthServiceToken())
    }

    @Test
    fun stsTokenRequiresExactRawIssuerPath() {
        listOf("/", "/healthapp/sts-extra", "/healthapp/sts/", "/healthapp/%73ts", "/healthapp/./sts").forEach { path ->
            cookies.clear()
            cookies.receive(URI("https://sts-hlth.io.mi.com$path"), listOf("serviceToken=wrong-path; Path=/"))
            assertEquals("", cookies.healthServiceToken())
        }
    }

    @Test
    fun replacementUpdatesProvenanceInsteadOfInheritingTrustedIssuer() {
        cookies.receive(sts, listOf("serviceToken=trusted; Domain=io.mi.com; Path=/"))
        cookies.receive(health, listOf("serviceToken=other-service; Domain=.io.mi.com; Path=/"))
        assertEquals("", cookies.healthServiceToken())
        assertEquals("serviceToken=other-service", cookies.header(health))
    }

    @Test
    fun accountIdentifierRequiresAccountIssuer() {
        cookies.receive(sts, listOf("cUserId=wrong-source; Domain=io.mi.com; Path=/"))
        assertEquals("", cookies.accountValue("cUserId"))
        cookies.receive(URI("https://c3.lp.account.xiaomi.com/poll"), listOf("cUserId=right-source"))
        assertEquals("right-source", cookies.accountValue("cUserId"))
        assertEquals("", cookies.accountValue("passToken"))
    }

    @Test
    fun cookiePathUsesDirectoryBoundaryAndDefaultDirectory() {
        cookies.receive(sts, listOf("userId=123"))
        assertEquals("userId=123", cookies.header(URI("https://sts-hlth.io.mi.com/healthapp/next")))
        assertEquals("userId=123", cookies.header(URI("https://sts-hlth.io.mi.com/healthapp")))
        assertEquals("", cookies.header(URI("https://sts-hlth.io.mi.com/healthapp-other")))
        assertEquals("", cookies.header(URI("https://sts-hlth.io.mi.com/")))
        cookies.receive(sts, listOf("deviceId=an_test; Path=/healthapp/sts"))
        assertEquals("deviceId=an_test; userId=123", cookies.header(sts))
        assertEquals("userId=123", cookies.header(URI("https://sts-hlth.io.mi.com/healthapp/sts-other")))
    }

    @Test
    fun encodedSlashDoesNotWidenCookiePath() {
        cookies.receive(sts, listOf("userId=123; Path=/healthapp"))
        assertEquals("", cookies.header(URI("https://sts-hlth.io.mi.com/healthapp%2Fsts")))
    }

    @Test
    fun emptyAndRelativePathsUseDefaultDirectory() {
        cookies.receive(sts, listOf("userId=123; Path=", "deviceId=an_test; Path=relative"))
        assertEquals("userId=123; deviceId=an_test", cookies.header(sts))
        assertEquals("", cookies.header(URI("https://sts-hlth.io.mi.com/")))
        cookies.receive(URI("https://account.xiaomi.com"), listOf("userId=root"))
        assertEquals("userId=root", cookies.header(account))
    }

    @Test
    fun unrelatedPublicSuffixAndMalformedDomainsAreRejected() {
        listOf(".com", "evil.com", "mi.com.evil.com", "..io.mi.com", "io.mi.com.", "hlth.io.mi.com", "").forEach { domain ->
            cookies.receive(sts, listOf("serviceToken=invalid; Domain=$domain; Path=/"))
        }
        cookies.receive(account, listOf("cUserId=invalid; Domain=mi.com; Path=/"))
        assertEquals("", cookies.header(sts))
        assertEquals("", cookies.header(account))
        assertEquals("", cookies.healthServiceToken())
    }

    @Test
    fun unsupportedOrInsecureDestinationsNeverReceiveCredentials() {
        cookies.receive(account, listOf("userId=123; Domain=.xiaomi.com; Path=/"))
        listOf(
            "http://account.xiaomi.com/", "https://account.xiaomi.com:444/",
            "https://user@account.xiaomi.com/", "https://account.xiaomi.com/#fragment",
            "https://account.xiaomi.com.evil.com/", "https://evil.com/", "https://www.xiaomi.com/",
        ).forEach { assertEquals("", cookies.header(URI(it))) }
        cookies.receive(URI("https://evil.com/"), listOf("serviceToken=bad; Domain=io.mi.com; Path=/"))
        assertEquals("", cookies.healthServiceToken())
    }

    @Test
    fun maxAgeOverridesExpiresAndExpiresAtTheExactBoundary() {
        cookies.receive(sts, listOf("serviceToken=short; Max-Age=2; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        now += 1999
        assertEquals("short", cookies.healthServiceToken())
        now++
        assertEquals("", cookies.healthServiceToken())
        assertEquals("", cookies.header(sts))
    }

    @Test
    fun zeroNegativeAndExpiredValuesDeleteMatchingCookie() {
        for (expiry in listOf("Max-Age=0", "Max-Age=-1", "Expires=Thu, 01 Jan 1970 00:00:00 GMT")) {
            cookies.receive(sts, listOf("serviceToken=present; Path=/"))
            cookies.receive(sts, listOf("serviceToken=; Path=/; $expiry"))
            assertEquals("", cookies.healthServiceToken())
            assertEquals("", cookies.header(sts))
        }
    }

    @Test
    fun legacyCookieDatesAndTwoDigitYearsAreParsedInUtc() {
        for (date in listOf("Tue, 01 Jan 2030 00:00:00 GMT", "Tue, 01-Jan-30 00:00:00 GMT", "Tue Jan 1 00:00:00 2030")) {
            cookies.clear()
            now = Instant.parse("2029-12-31T23:59:59Z").toEpochMilli()
            cookies.receive(sts, listOf("serviceToken=present; Expires=$date"))
            assertEquals("present", cookies.healthServiceToken())
            now += 1000
            assertEquals("", cookies.healthServiceToken())
        }
    }

    @Test
    fun invalidExpiryAttributesDoNotOverrideValidExpiryAndHugeMaxAgeDoesNotOverflow() {
        cookies.receive(sts, listOf("serviceToken=present; Max-Age=1; Max-Age=invalid; Expires=invalid"))
        now += 1000
        assertEquals("", cookies.healthServiceToken())
        cookies.receive(sts, listOf("serviceToken=long-lived; Max-Age=9999999999999999999999999"))
        now += 86400_000
        assertEquals("long-lived", cookies.healthServiceToken())
        cookies.receive(sts, listOf("serviceToken=; Max-Age=000000000000000000000000000000"))
        assertEquals("", cookies.healthServiceToken())
    }

    @Test
    fun onlySmallSafeCredentialCookiesAreRetained() {
        cookies.receive(sts, listOf(
            "passToken=secret; Path=/", "analytics=tracking; Path=/", "serviceToken=a,b; Path=/",
            "serviceToken=a\\b; Path=/", "serviceToken=a b; Path=/", "serviceToken=汉字; Path=/",
            "serviceToken=a\r\nInjected=bad; Path=/", "serviceToken=" + "a".repeat(4097),
            "serviceToken=bad; Path=/" + "x".repeat(2048), "x".repeat(8193),
        ))
        assertEquals("", cookies.header(sts))
        cookies.receive(sts, listOf("serviceToken=\"abc+/=~\"; Secure; HttpOnly; SameSite=None"))
        assertEquals("abc+/=~", cookies.healthServiceToken())
        assertEquals("serviceToken=abc+/=~", cookies.header(sts))
        assertFalse(cookies.toString().contains("abc+/=~"))
    }

    @Test
    fun storageIsCappedAtThirtyTwoCookiesAndClearingRemovesAllCredentials() {
        for (index in 0..32) cookies.receive(account, listOf("userId=$index; Path=/p$index"))
        assertEquals("", cookies.header(URI("https://account.xiaomi.com/p0")))
        for (index in 1..32) assertEquals("userId=$index", cookies.header(URI("https://account.xiaomi.com/p$index")))
        assertEquals("32", cookies.accountValue("userId"))
        cookies.receive(sts, listOf("serviceToken=present"))
        cookies.clear()
        assertEquals("", cookies.healthServiceToken())
        assertEquals("", cookies.accountValue("userId"))
        assertTrue(cookies.header(sts).isEmpty())
    }

    @Test
    fun longestCookiePathFirstAndReplacingCookiePreservesCreationOrder() {
        cookies.receive(sts, listOf("userId=old; Path=/", "cUserId=second; Path=/", "deviceId=narrow; Path=/healthapp"))
        cookies.receive(sts, listOf("userId=new; Path=/"))
        assertEquals("deviceId=narrow; userId=new; cUserId=second", cookies.header(sts))
    }
}
