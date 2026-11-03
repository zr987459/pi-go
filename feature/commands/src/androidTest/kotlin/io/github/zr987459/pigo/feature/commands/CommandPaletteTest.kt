package io.github.zr987459.pigo.feature.commands

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.commands.ui.CommandPalette
import io.github.zr987459.pigo.feature.commands.ui.PaletteGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandPaletteTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun groupsAndPick() {
        val cmds = FakeEngineExtras.FIXTURE_COMMANDS
        val picked = mutableListOf<SlashCommand>()
        rule.setContent {
            CommandPalette(
                groups = listOf(
                    PaletteGroup("技能", cmds.filter { it.source == CommandSource.SKILL }),
                    PaletteGroup("提示模板", cmds.filter { it.source == CommandSource.PROMPT }),
                ),
                loading = false,
                onPick = { picked += it },
            )
        }
        rule.onNodeWithText("技能").assertIsDisplayed()
        rule.onNodeWithText("修复失败的测试").assertIsDisplayed()
        rule.onNodeWithTag("cmd-fix-tests").performClick()
        assertEquals(listOf("fix-tests"), picked.map { it.name })
    }
}
