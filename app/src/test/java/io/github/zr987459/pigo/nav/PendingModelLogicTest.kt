package io.github.zr987459.pigo.nav

import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CatalogModel
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审查第 1 轮 Important 3 与 Nit：配好服务商回聊天，选回的是用户点的那个模型；清除 / 未配置不回聊天。 */
class PendingModelLogicTest {
    private fun entry(id: String, ready: Boolean) =
        CatalogEntry(CatalogModel(provider = "anthropic", id = id), if (ready) ModelAvailability.READY else ModelAvailability.NEEDS_AUTH)

    private val entries = listOf(entry("claude-haiku", true), entry("claude-sonnet-4-5", true))

    @Test
    fun picksTheClickedModelNotTheFirstOne() {
        val t = PendingModel.Target("anthropic", ModelRef("anthropic", "claude-sonnet-4-5"))
        assertEquals(ModelRef("anthropic", "claude-sonnet-4-5"), PendingModelLogic.pick(t, entries))
    }

    @Test
    fun providerHeaderFallsBackToFirstReadyModel() {
        assertEquals(ModelRef("anthropic", "claude-haiku"), PendingModelLogic.pick(PendingModel.Target("anthropic", null), entries))
    }

    @Test
    fun returnsToChatOnlyWhenThatProviderBecameConfigured() {
        val t = PendingModel.Target("anthropic", ModelRef("anthropic", "claude-sonnet-4-5"))
        assertTrue(PendingModelLogic.shouldReturn(t, "anthropic", configured = true))
        assertFalse("点「清除」后未配置", PendingModelLogic.shouldReturn(t, "anthropic", configured = false))
        assertFalse("别的服务商", PendingModelLogic.shouldReturn(t, "openai", configured = true))
        assertFalse("没有待选模型（从设置首页进来）", PendingModelLogic.shouldReturn(null, "anthropic", configured = true))
    }
}

class ThumbnailsTest {
    @Test
    fun sampleSizeKeepsAtLeastTarget() {
        assertEquals(1, Thumbnails.sampleSize(100, 100, 96))
        assertEquals(16, Thumbnails.sampleSize(4000, 3000, 96))
        assertEquals(1, Thumbnails.sampleSize(0, 0, 96))
    }
}
