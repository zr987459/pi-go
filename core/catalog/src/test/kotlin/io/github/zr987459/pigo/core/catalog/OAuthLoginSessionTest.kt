package io.github.zr987459.pigo.core.catalog

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OAuthLoginSessionTest {
    private class FakeProc : LineProcess {
        val out = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var stopped = false
        override val lines: Flow<String> = out.consumeAsFlow()
        override suspend fun send(line: String) { sent += line }
        override suspend fun stop() { stopped = true; out.close() }
    }

    @Test fun protocolParsingIgnoresNoiseAndRedacts() {
        assertEquals(null, OAuthProtocol.parse("(node:1) ExperimentalWarning"))
        val e = OAuthProtocol.parse("""{"type":"error","message":"bad token ghu_abcdefghijk123456","cancelled":false}""") as LoginEvent.Failed
        assertFalse(e.message.contains("ghu_abcdefghijk123456"))
        val p = OAuthProtocol.parse("""{"type":"prompt","id":"p2","prompt":{"type":"select","message":"m","options":[{"id":"a","label":"A"}]}}""") as LoginEvent.Prompt
        assertEquals("select", p.kind)
        assertEquals("A", p.options.single().label)
        assertEquals("""{"type":"answer","id":"p1","value":"x\"y"}""", OAuthProtocol.answer("p1", "x\"y"))
    }

    @Test fun deviceCodeFlowSucceeds() = runTest {
        val proc = FakeProc()
        var finished: Boolean? = null
        val s = OAuthLoginSession("github-copilot", proc, backgroundScope) { finished = it }
        proc.out.send("""{"type":"prompt","id":"p1","prompt":{"type":"text","message":"GitHub Enterprise URL/domain (blank for github.com)"}}""")
        advanceUntilIdle()
        assertTrue(s.state.value is OAuthLoginSession.State.AwaitingInput)
        s.answer("p1", "")
        assertEquals("""{"type":"answer","id":"p1","value":""}""", proc.sent.single())
        proc.out.send("""{"type":"event","event":{"type":"device_code","userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","expiresInSeconds":900}}""")
        advanceUntilIdle()
        val dc = s.state.value as OAuthLoginSession.State.AwaitingDeviceCode
        assertEquals("ABCD-1234", dc.userCode)
        proc.out.send("""{"type":"event","event":{"type":"progress","message":"Enabling models..."}}""")
        advanceUntilIdle()
        assertTrue("设备码页面在轮询期间保持", s.state.value is OAuthLoginSession.State.AwaitingDeviceCode)
        proc.out.send("""{"type":"done","provider":"github-copilot","credential":"oauth"}""")
        advanceUntilIdle()
        assertEquals(OAuthLoginSession.State.Succeeded, s.state.value)
        assertEquals(true, finished)
    }

    @Test fun pkcePasteWithWrongStateIsRejectedBeforeReachingPi() = runTest {
        val proc = FakeProc()
        val s = OAuthLoginSession("anthropic", proc, backgroundScope)
        proc.out.send("""{"type":"event","event":{"type":"auth_url","url":"https://claude.ai/oauth/authorize?state=GOOD&redirect_uri=http%3A%2F%2Flocalhost%3A53692%2Fcallback"}}""")
        proc.out.send("""{"type":"prompt","id":"p1","prompt":{"type":"manual_code","message":"paste"}}""")
        advanceUntilIdle()
        val st = s.state.value as OAuthLoginSession.State.AwaitingBrowser
        assertEquals("p1", st.pendingPrompt!!.id)
        try {
            s.answer("p1", "code#EVIL"); fail("应拒绝")
        } catch (_: Pkce.StateMismatchException) {
        }
        assertTrue(proc.sent.isEmpty())
        assertTrue(s.deliverRedirect("http://localhost:53692/callback?code=c&state=GOOD"))
        assertTrue(proc.sent.single().contains("state=GOOD"))
    }

    @Test fun cancelStopsProcessAndProcessDeathFails() = runTest {
        val proc = FakeProc()
        val s = OAuthLoginSession("xai", proc, backgroundScope)
        advanceUntilIdle()
        s.cancel()
        assertTrue(proc.stopped)
        assertTrue(proc.sent.single().contains("cancel"))
        assertEquals(true, (s.state.value as OAuthLoginSession.State.Failed).cancelled)

        val proc2 = FakeProc()
        val s2 = OAuthLoginSession("xai", proc2, backgroundScope)
        proc2.out.close()
        advanceUntilIdle()
        assertFalse((s2.state.value as OAuthLoginSession.State.Failed).cancelled)
    }
}
