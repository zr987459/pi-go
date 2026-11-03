package io.github.zr987459.pigo.core.session

import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModuleSkeletonTest {
    @Test
    fun moduleSeesContractCatalog() {
        assertTrue(SessionModule.actionSpecs.isNotEmpty())
    }
}
