package io.github.zr987459.pigo.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    @Test fun keyCleanAndMask() {
        assertEquals("sk-xxxx", KeyText.clean("  Bearer sk-xxxx  "))
        assertEquals("", KeyText.clean("  Bearer  "))
        assertEquals("••••WXYZ", KeyText.mask("sk-abcdef1234WXYZ"))
        assertEquals("••••", KeyText.mask("sk-xxxx")) // 太短，不显示末 4 位
        assertEquals("未设置", KeyText.mask(null))
        assertEquals("••••WXYZ", KeyText.preview(" Bearer sk-abcdef1234WXYZ "))
        assertNull(KeyText.preview("   "))
        assertFalse(KeyText.preview("sk-abcdef1234WXYZ")!!.contains("abcdef"))
    }

    @Test fun maskFromLast4OnlyNeverNeedsPlaintext() {
        assertEquals("••••WXYZ", KeyText.maskLast4("WXYZ"))
        assertEquals("••••", KeyText.maskLast4(null)) // 短 Key：SecretStore.last4 返回 null
        assertEquals("••••", KeyText.maskLast4(""))
    }

    @Test fun envVarAndModels() {
        assertEquals("PIGO_KEY_BAILIAN", KeyText.envVarFor("bailian"))
        assertEquals("PIGO_KEY_MY_API", KeyText.envVarFor(" My API "))
        assertEquals("PIGO_KEY_<ID>", KeyText.envVarFor(""))
        assertEquals(listOf("qwen-plus", "qwen-max", "glm-4.6"), KeyText.splitModels("qwen-plus, qwen-max，glm-4.6\nqwen-plus"))
        assertEquals("key.deepseek", KeyText.builtInAlias("deepseek"))
        assertEquals("key.custom.bailian", KeyText.customAlias("bailian"))
    }

    @Test fun romVendorDetection() {
        assertEquals(RomGuide.Vendor.XIAOMI, RomGuide.vendorOf("Xiaomi", "Redmi"))
        assertEquals(RomGuide.Vendor.HUAWEI, RomGuide.vendorOf("HUAWEI", "HUAWEI"))
        assertEquals(RomGuide.Vendor.HONOR, RomGuide.vendorOf("HONOR", "HONOR"))
        assertEquals(RomGuide.Vendor.OPPO, RomGuide.vendorOf("OPPO", "OPPO"))
        assertEquals(RomGuide.Vendor.OPPO, RomGuide.vendorOf("realme", "realme"))
        assertEquals(RomGuide.Vendor.VIVO, RomGuide.vendorOf("vivo", "iQOO"))
        assertEquals(RomGuide.Vendor.ONEPLUS, RomGuide.vendorOf("OnePlus", "OnePlus"))
        assertEquals(RomGuide.Vendor.SAMSUNG, RomGuide.vendorOf("samsung", "samsung"))
        assertEquals(RomGuide.Vendor.OTHER, RomGuide.vendorOf("Google", "google"))
        assertEquals(RomGuide.Vendor.OTHER, RomGuide.vendorOf(null, null))
    }

    @Test fun romGuideContent() {
        for (v in RomGuide.Vendor.entries) {
            assertTrue(RomGuide.steps(v).isNotEmpty())
            RomGuide.steps(v).forEach { assertTrue("步骤应为中文：$it", it.any { c -> c.code > 0x4e00 }) }
        }
        assertTrue(RomGuide.isAggressive(RomGuide.Vendor.XIAOMI))
        assertFalse(RomGuide.isAggressive(RomGuide.Vendor.OTHER))
        assertTrue(RomGuide.autostartComponents(RomGuide.Vendor.XIAOMI).isNotEmpty())
        assertTrue(RomGuide.autostartComponents(RomGuide.Vendor.OTHER).isEmpty())
        assertTrue(RomGuide.needsPhantomNotice(31))
        assertFalse(RomGuide.needsPhantomNotice(30))
        assertTrue(RomGuide.PHANTOM_TEXT.contains("max_phantom_processes"))
    }
}
