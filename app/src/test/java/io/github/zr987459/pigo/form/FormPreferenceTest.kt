package io.github.zr987459.pigo.form

import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 形态偏好：启动读回上次形态，切换后写回 App 设置（DESIGN-r3 §5.1）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormPreferenceTest {
    private class MemStore(var value: UiForm? = null, val throwOnLoad: Boolean = false) : FormPrefStore {
        val saves = mutableListOf<UiForm>()
        override fun load(): UiForm? = if (throwOnLoad) error("坏的偏好文件") else value
        override fun save(form: UiForm) { saves += form; value = form }
    }

    @Test
    fun firstLaunchDefaultsToCalm() {
        assertEquals(UiForm.CALM, FormPersistence.initial(MemStore()))
        assertEquals(UiForm.CALM, FormPersistence.initial(MemStore(throwOnLoad = true)))
    }

    @Test
    fun restoresLastForm() {
        assertEquals(UiForm.TERM, FormPersistence.initial(MemStore(UiForm.TERM)))
    }

    @Test
    fun parseIsStrict() {
        assertEquals(UiForm.TERM, FormPersistence.parse("TERM"))
        assertEquals(UiForm.CALM, FormPersistence.parse("CALM"))
        assertNull(FormPersistence.parse("term"))
        assertNull(FormPersistence.parse(""))
        assertNull(FormPersistence.parse(null))
    }

    @Test
    fun bindWritesEachChangeButNotTheInitialValue() = runTest {
        val store = MemStore(UiForm.CALM)
        val form = MutableStateFlow(FormPersistence.initial(store))
        FormPersistence.bind(form, store, backgroundScope)
        runCurrent()
        assertEquals(emptyList<UiForm>(), store.saves)

        form.value = UiForm.TERM
        runCurrent()
        form.value = UiForm.TERM
        runCurrent()
        form.value = UiForm.CALM
        runCurrent()
        assertEquals(listOf(UiForm.TERM, UiForm.CALM), store.saves)
        assertEquals(UiForm.CALM, FormPersistence.initial(store))
    }

    @Test
    fun toggleAlternates() {
        assertEquals(UiForm.TERM, FormSwitch.next(UiForm.CALM))
        assertEquals(UiForm.CALM, FormSwitch.next(UiForm.TERM))
    }
}
