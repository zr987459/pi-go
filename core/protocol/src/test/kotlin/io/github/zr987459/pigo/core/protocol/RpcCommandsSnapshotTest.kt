package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.rpc.RpcCommand
import io.github.zr987459.pigo.contract.rpc.RpcCommands
import io.github.zr987459.pigo.contract.rpc.RpcImage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个新命令的 JSON 与 pi 1.5.0 `rpc-types.d.ts`（测试资源里的原样副本）一致：
 * 1) 序列化快照（逐字比较整行 JSON）；
 * 2) 从 d.ts 解析出每个 `type` 的字段表，断言 JSON 里每个键都在表里、表里的必填键都在 JSON 里；
 * 3) 1.5.0 的全部命令 `type` 都有对应的 Kotlin 类型。
 */
class RpcCommandsSnapshotTest {

    /** d.ts 的 `export type RpcCommand = {…} | {…};` → type → (字段 → 是否可选)。 */
    private val schema: Map<String, Map<String, Boolean>> by lazy {
        val text = javaClass.getResourceAsStream("/pi-1.5.0/rpc-types.d.ts")!!.readBytes().toString(Charsets.UTF_8)
        val union = text.substringAfter("export type RpcCommand =").substringBefore("};\n") + "}"
        Regex("\\{([^{}]*)\\}").findAll(union).associate { m ->
            val fields = m.groupValues[1].split(';').map { it.trim() }.filter { it.isNotEmpty() }.associate { f ->
                val name = f.substringBefore(':').trim()
                name.removeSuffix("?") to name.endsWith("?")
            }
            val type = Regex("type:\\s*\"([a-z_]+)\"").find(m.groupValues[1])!!.groupValues[1]
            type to fields.filterKeys { it != "type" }
        }
    }

    private val img = RpcImage("iVBORw0KGgo=", "image/png")

    private val samples: List<Pair<RpcCommand, String>> = listOf(
        RpcCommands.PromptWithImages("c-1", "看图", listOf(img)) to
            """{"id":"c-1","type":"prompt","message":"看图","images":[{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}]}""",
        RpcCommands.PromptWithImages("c-2", "再看", listOf(img), RpcCommand.STREAMING_STEER) to
            """{"id":"c-2","type":"prompt","message":"再看","images":[{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}],"streamingBehavior":"steer"}""",
        RpcCommands.PromptWithImages("c-3", "无图", emptyList()) to """{"id":"c-3","type":"prompt","message":"无图"}""",
        RpcCommands.Steer("c-4", "插话", listOf(img)) to
            """{"id":"c-4","type":"steer","message":"插话","images":[{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}]}""",
        RpcCommands.FollowUp("c-5", "之后") to """{"id":"c-5","type":"follow_up","message":"之后"}""",
        RpcCommands.NewSessionWithParent("c-6", "/root/.pi/agent/sessions/a.jsonl") to
            """{"id":"c-6","type":"new_session","parentSession":"/root/.pi/agent/sessions/a.jsonl"}""",
        RpcCommands.CycleModel("c-7") to """{"id":"c-7","type":"cycle_model"}""",
        RpcCommands.CycleThinkingLevel("c-8") to """{"id":"c-8","type":"cycle_thinking_level"}""",
        RpcCommands.SetSteeringMode("c-9", RpcCommands.MODE_ONE_AT_A_TIME) to """{"id":"c-9","type":"set_steering_mode","mode":"one-at-a-time"}""",
        RpcCommands.SetFollowUpMode("c-10", RpcCommands.MODE_ALL) to """{"id":"c-10","type":"set_follow_up_mode","mode":"all"}""",
        RpcCommands.Compact("c-11") to """{"id":"c-11","type":"compact"}""",
        RpcCommands.Compact("c-12", "只保留结论") to """{"id":"c-12","type":"compact","customInstructions":"只保留结论"}""",
        RpcCommands.SetAutoCompaction("c-13", false) to """{"id":"c-13","type":"set_auto_compaction","enabled":false}""",
        RpcCommands.SetAutoRetry("c-14", true) to """{"id":"c-14","type":"set_auto_retry","enabled":true}""",
        RpcCommands.Bash("c-15", "echo hi") to """{"id":"c-15","type":"bash","command":"echo hi"}""",
        RpcCommands.Bash("c-16", "ls\n-la", excludeFromContext = true) to
            """{"id":"c-16","type":"bash","command":"ls\n-la","excludeFromContext":true}""",
        RpcCommands.AbortBash("c-17") to """{"id":"c-17","type":"abort_bash"}""",
        RpcCommands.ExportHtml("c-18") to """{"id":"c-18","type":"export_html"}""",
        RpcCommands.ExportHtml("c-19", "/tmp/a.html") to """{"id":"c-19","type":"export_html","outputPath":"/tmp/a.html"}""",
        RpcCommands.Fork("c-20", "e1a2") to """{"id":"c-20","type":"fork","entryId":"e1a2"}""",
        RpcCommands.Clone("c-21") to """{"id":"c-21","type":"clone"}""",
        RpcCommands.GetForkMessages("c-22") to """{"id":"c-22","type":"get_fork_messages"}""",
        RpcCommands.GetEntries("c-23") to """{"id":"c-23","type":"get_entries"}""",
        RpcCommands.GetEntries("c-24", "e9") to """{"id":"c-24","type":"get_entries","since":"e9"}""",
        RpcCommands.GetTree("c-25") to """{"id":"c-25","type":"get_tree"}""",
        RpcCommands.GetLastAssistantText("c-26") to """{"id":"c-26","type":"get_last_assistant_text"}""",
        RpcCommands.GetCommands("c-27") to """{"id":"c-27","type":"get_commands"}""",
    )

    @Test
    fun serializationSnapshots() {
        for ((cmd, expected) in samples) {
            assertEquals(cmd.type, expected, RecordCodec.encode(cmd))
            // 一条命令就是一行（字符串里的换行被转义）
            assertTrue(!RecordCodec.encode(cmd).contains('\n'))
        }
    }

    @Test
    fun fieldsMatchRpcTypesDts() {
        assertTrue("d.ts 解析失败", schema.size >= 30)
        for ((cmd, _) in samples) {
            val json: JsonObject = cmd.toJson()
            val fields = schema[cmd.type] ?: error("d.ts 里没有命令 ${cmd.type}")
            for (k in json.keys) {
                if (k == "type") continue
                assertTrue("${cmd.type} 多出了 d.ts 没有的字段 $k", k in fields)
            }
            for ((k, optional) in fields) {
                if (!optional) assertTrue("${cmd.type} 缺少必填字段 $k", k in json)
            }
        }
    }

    @Test
    fun everyCommandTypeIsCovered() {
        assertEquals(schema.keys, RpcCommands.ALL_TYPES.toSet())
        val covered = samples.map { it.first.type }.toSet() + setOf(
            // 已有类型（RpcCommand.kt 上半部分，mvp/engine 已有快照测试）
            "abort", "clear_queue", "get_state", "set_model", "get_available_models", "set_thinking_level",
            "get_available_thinking_levels", "abort_retry", "get_session_stats", "switch_session", "set_session_name", "get_messages",
        )
        assertEquals("没有 Kotlin 类型的命令", emptySet<String>(), schema.keys - covered)
    }

    @Test
    fun imagesRoundTrip() {
        val imgs = listOf(img, RpcImage("/9j/4AAQ", "image/jpeg"))
        val json = RpcCommands.PromptWithImages("c-1", "两张", imgs).toJson()
        assertEquals(imgs, ExtraResponses.images(json["images"]))
        val decoded = RecordCodec.json.parseToJsonElement(RecordCodec.encode(RpcCommands.Steer("c-2", "x", imgs))) as JsonObject
        assertEquals(imgs, ExtraResponses.images(decoded["images"]))
    }
}
