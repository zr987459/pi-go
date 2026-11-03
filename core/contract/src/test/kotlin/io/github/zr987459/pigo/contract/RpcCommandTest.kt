package io.github.zr987459.pigo.contract

import io.github.zr987459.pigo.contract.fake.FakeAppLog
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RpcCommandTest {

    @Test
    fun promptWithStreamingBehavior() {
        val json = RpcCommand.Prompt("c-2", "做完再跑一遍测试", RpcCommand.STREAMING_FOLLOW_UP).toJson().toString()
        assertEquals("""{"id":"c-2","type":"prompt","message":"做完再跑一遍测试","streamingBehavior":"followUp"}""", json)
    }

    @Test
    fun idlePromptHasNoStreamingBehavior() {
        val json = RpcCommand.Prompt("c-1", "hi").toJson()
        assertFalse(json.containsKey("streamingBehavior"))
    }

    @Test
    fun extensionUiResponseUsesRequestIdAndNoClientId() {
        assertEquals(
            """{"type":"extension_ui_response","id":"ui-7","cancelled":true}""",
            RpcCommand.ExtensionUiResponse("ui-7", cancelled = true).toJson().toString(),
        )
        assertEquals(
            """{"type":"extension_ui_response","id":"ui-8","value":"Allow"}""",
            RpcCommand.ExtensionUiResponse("ui-8", value = "Allow").toJson().toString(),
        )
        assertEquals(
            """{"type":"extension_ui_response","id":"ui-9","confirmed":false}""",
            RpcCommand.ExtensionUiResponse("ui-9", confirmed = false).toJson().toString(),
        )
        // 什么都没给：退化为 cancelled，而不是发出 value:null
        assertEquals(
            """{"type":"extension_ui_response","id":"ui-10","cancelled":true}""",
            RpcCommand.ExtensionUiResponse("ui-10").toJson().toString(),
        )
    }

    @Test
    fun sanitizeKey() {
        assertEquals("sk-xxxx", sanitizeApiKey("  Bearer sk-xxxx  "))
    }

    @Test
    fun fakeLogRedacts() {
        val log = FakeAppLog()
        log.i("t", "key=sk-abcdef123456 auth=Bearer abcdefghijk")
        assertEquals("key=*** auth=***", log.tail(1).single().message)
    }
}
