package io.github.zr987459.pigo.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun rfc7636AppendixBVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val p = Pkce.generate()
        assertEquals(43, p.verifier.length)
        assertEquals(Pkce.challengeOf(p.verifier), p.challenge)
        assertFalse(p.verifier.contains('=') || p.verifier.contains('+') || p.verifier.contains('/'))
    }

    @Test fun parsesAllInputShapesLikePi() {
        assertEquals(Pkce.AuthInput("abc", "xyz"), Pkce.parseAuthorizationInput("http://localhost:53692/callback?code=abc&state=xyz"))
        assertEquals(Pkce.AuthInput("a b", "s"), Pkce.parseAuthorizationInput("https://h/cb?state=s&code=a%20b"))
        assertEquals(Pkce.AuthInput("abc", "xyz"), Pkce.parseAuthorizationInput("abc#xyz"))
        assertEquals(Pkce.AuthInput("abc", "xyz"), Pkce.parseAuthorizationInput("code=abc&state=xyz"))
        assertEquals(Pkce.AuthInput("abc", null), Pkce.parseAuthorizationInput("  abc  "))
        assertEquals(Pkce.AuthInput(null, null), Pkce.parseAuthorizationInput(""))
    }

    @Test fun stateIsValidatedAgainstAuthUrl() {
        val authUrl = "https://claude.ai/oauth/authorize?code=true&client_id=x&state=S123&redirect_uri=http%3A%2F%2Flocalhost%3A53692%2Fcallback"
        assertEquals("S123", Pkce.stateOf(authUrl))
        assertEquals("c1", Pkce.validate(authUrl, "c1#S123").code)
        assertEquals("c2", Pkce.validate(authUrl, "c2").code) // 只粘 code：交给 pi 用自己的 state
        assertThrows(Pkce.StateMismatchException::class.java) { Pkce.validate(authUrl, "c1#EVIL") }
        assertThrows(Pkce.StateMismatchException::class.java) {
            Pkce.validate(authUrl, "http://localhost:53692/callback?code=c&state=EVIL")
        }
        assertThrows(IllegalArgumentException::class.java) { Pkce.validate(authUrl, "#S123") }
    }

    @Test fun loopbackCallbackRecognition() {
        val ru = "http://localhost:53692/callback"
        assertTrue(Pkce.isLoopbackCallback("http://localhost:53692/callback?code=x", ru))
        assertTrue(Pkce.isLoopbackCallback("http://127.0.0.1:53692/callback?code=x", ru))
        assertFalse(Pkce.isLoopbackCallback("http://localhost:1/callback?code=x", ru))
        assertFalse(Pkce.isLoopbackCallback("https://evil.example/callback?code=x", ru))
        assertEquals(ru, RealAuthManager.redirectPrefix("https://a/authorize?state=1&redirect_uri=http%3A%2F%2Flocalhost%3A53692%2Fcallback"))
        assertNull(RealAuthManager.redirectPrefix("https://a/authorize?redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"))
    }
}
