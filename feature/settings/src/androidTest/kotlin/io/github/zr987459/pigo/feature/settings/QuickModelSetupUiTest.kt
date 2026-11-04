package io.github.zr987459.pigo.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r4/model-config 主流程：选服务商 → 填 Key → 选模型 → 保存 → 当前会话用上这个模型、能发消息（假引擎）。
 * 保存动作按真实装配的顺序：重启引擎 → set_model（见 core/store QuickModelSetup，单测 QuickModelSetupTest 验写入格式）。
 */
@RunWith(AndroidJUnit4::class)
class QuickModelSetupUiTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val saved = mutableListOf<String>()
    private val current = MutableStateFlow<String?>(null)

    private val actions = QuickSetupActions(
        presets = listOf(
            QuickPreset("deepseek", "DeepSeek", listWithoutKey = true),
            QuickPreset("custom-openai", "其他（OpenAI 兼容）", needsBaseUrl = true),
        ),
        currentModel = current,
        loadModels = { id, key, url ->
            when (id) {
                "deepseek" -> Outcome.Ok(listOf("deepseek-chat", "deepseek-reasoner"))
                else -> if (key.isBlank()) Outcome.Err(ErrCode.PROVIDER, "先填写 API Key") else Outcome.Ok(listOf("m-$url"))
            }
        },
        test = { _, key, model, _ -> if (key == "sk-good") QuickCheck(true, "连接成功，可以用 $model 聊天了") else QuickCheck(false, "Key 不对或没有权限，请重新复制 Key（HTTP 401）") },
        save = { id, key, model, _, _ ->
            saved += "$id|$key|$model"
            engine.restart("模型配置已更新")
            when (val r = engine.setModel(id, model)) {
                is Outcome.Ok -> { current.value = "$id/$model"; Outcome.Ok("已保存，现在用 DeepSeek · $model 聊天") }
                is Outcome.Err -> r
            }
        },
    )

    @Test fun pickProviderTypeKeyPickModelSaveThenChatUsesIt() {
        rule.setContent { QuickModelSetupPage(actions) }
        rule.onNodeWithTag(QuickTags.CURRENT).assertTextContains("还没有", substring = true)
        rule.onNodeWithTag(QuickTags.preset("deepseek")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(QuickTags.model("deepseek-reasoner"))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(QuickTags.KEY).performTextInput("sk-bad")
        list().performScrollToNode(hasTestTag(QuickTags.model("deepseek-reasoner")))
        rule.onNodeWithTag(QuickTags.model("deepseek-reasoner")).performClick()

        // 测试连接：错 Key 给明确的中文原因
        list().performScrollToNode(hasTestTag(QuickTags.TEST))
        rule.onNodeWithTag(QuickTags.TEST).performClick()
        list().performScrollToNode(hasTestTag(QuickTags.RESULT))
        rule.onNodeWithTag(QuickTags.RESULT).assertTextContains("Key 不对", substring = true)

        list().performScrollToNode(hasTestTag(QuickTags.SAVE))
        rule.onNodeWithTag(QuickTags.SAVE).performClick()
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(listOf("deepseek|sk-bad|deepseek-reasoner"), saved)
        list().performScrollToNode(hasTestTag(QuickTags.RESULT))
        rule.onNodeWithTag(QuickTags.RESULT).assertTextContains("已保存", substring = true)

        // 回到聊天：当前会话就是这个模型，发消息可用
        assertEquals("deepseek-reasoner", engine.chat.value.model?.id)
        assertTrue(runBlocking { engine.send("你好", SendMode.STEER) } is Outcome.Ok)
        list().performScrollToNode(hasTestTag(QuickTags.CURRENT))
        rule.onNodeWithTag(QuickTags.CURRENT).assertTextContains("deepseek/deepseek-reasoner", substring = true)
    }

    @Test fun customEndpointNeedsOnlyBaseUrlAndKey() {
        rule.setContent { QuickModelSetupPage(actions, onAdvanced = {}) }
        rule.onNodeWithTag(QuickTags.preset("custom-openai")).performClick()
        rule.onNodeWithTag(QuickTags.BASE_URL).assertIsDisplayed().performTextInput("https://api.example.com/v1")
        rule.onNodeWithTag(QuickTags.KEY).performTextInput("sk-good")
        list().performScrollToNode(hasTestTag(QuickTags.FETCH))
        rule.onNodeWithTag(QuickTags.FETCH).performClick()
        val id = "m-https://api.example.com/v1"
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(QuickTags.model(id))).fetchSemanticsNodes().isNotEmpty() }
        list().performScrollToNode(hasTestTag(QuickTags.TEST))
        rule.onNodeWithTag(QuickTags.TEST).performClick()
        list().performScrollToNode(hasTestTag(QuickTags.RESULT))
        rule.onNodeWithTag(QuickTags.RESULT).assertTextContains("连接成功", substring = true)
        list().performScrollToNode(hasTestTag(QuickTags.ADVANCED))
        rule.onNodeWithTag(QuickTags.ADVANCED).assertIsDisplayed()
    }

    private fun list() = rule.onNodeWithTag(QuickTags.LIST)
}
