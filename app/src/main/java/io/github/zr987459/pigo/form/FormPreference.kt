package io.github.zr987459.pigo.form

import android.content.Context
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 「上次形态」的 App 设置（DESIGN-r3 §5.1：`form` 存 App 设置，只被 FormHost 与 App 设置读取）。
 * 只存这一项；pi 自己的设置不在这里。
 */
interface FormPrefStore {
    /** 上次保存的形态；从没保存过或值无法识别时为 null。 */
    fun load(): UiForm?

    fun save(form: UiForm)
}

/** SharedPreferences 实现（`pigo_app.xml` 的 `ui_form` 键，存枚举名）。 */
class SharedPrefsFormStore(context: Context) : FormPrefStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): UiForm? = FormPersistence.parse(prefs.getString(KEY, null))

    override fun save(form: UiForm) {
        prefs.edit().putString(KEY, form.name).apply()
    }

    companion object {
        const val PREFS = "pigo_app"
        const val KEY = "ui_form"
    }
}

/** 形态偏好的读取与回写（纯逻辑，JVM 单测见 FormPreferenceTest）。 */
object FormPersistence {
    /** 首次启动默认 UI 形态（DESIGN-r3 §0：先交付 UI 形态，用户日常用）。 */
    val DEFAULT: UiForm = UiForm.CALM

    fun parse(raw: String?): UiForm? = raw?.let { r -> UiForm.entries.firstOrNull { it.name == r } }

    /**
     * 终端形态是否已接线（r3/term 合入、r3/app PR 2 接线后改为 true）。PR 1 阶段终端只是占位页（[TermPlaceholder]）。
     */
    const val TERM_WIRED: Boolean = true

    /**
     * 启动时的形态：读回上次形态；终端形态还没接线时回落到 UI 形态，不会一启动就停在「终端形态尚未合入」占位页
     * （PR #21 审查第 1 轮 Nit 4）。偏好本身不改写，PR 2 接线后照常恢复终端。
     */
    fun initial(store: FormPrefStore, termWired: Boolean = TERM_WIRED): UiForm {
        val f = runCatching { store.load() }.getOrNull() ?: DEFAULT
        return if (f == UiForm.TERM && !termWired) UiForm.CALM else f
    }

    /**
     * 订阅 [form]，每次变化写回 [store]（跳过订阅时的当前值，相同值不重复写）。
     * 只写 App 设置，不碰引擎、不发任何 RPC（硬性要求 ②）。
     */
    fun bind(form: StateFlow<UiForm>, store: FormPrefStore, scope: CoroutineScope): Job =
        scope.launch {
            form.drop(1).distinctUntilChanged().collect { f -> runCatching { store.save(f) } }
        }
}
