package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.core.store.QuickModelSetup
import io.github.zr987459.pigo.feature.settings.QuickCheck
import io.github.zr987459.pigo.feature.settings.QuickPreset
import io.github.zr987459.pigo.feature.settings.QuickSaveRunner
import io.github.zr987459.pigo.feature.settings.QuickSetupActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 设置 › 模型 极简页的装配（r4/model-config）：`core/store` 的 [QuickModelSetup] → 页面的 [QuickSetupActions]。界面测试走同一份接线。 */
object QuickSetupWiring {
    /** 「保存并使用」放在 [scope]（App 级）里跑；[onFinished] 结束时弹全局提示。 */
    fun runner(setup: QuickModelSetup, scope: CoroutineScope, onFinished: (QuickCheck) -> Unit = {}): QuickSaveRunner =
        QuickSaveRunner(scope, save = { req, progress ->
            when (val r = setup.save(req.presetId, req.key, req.model, req.models, req.baseUrl, onProgress = progress)) {
                is Outcome.Ok -> Outcome.Ok(r.value.message)
                is Outcome.Err -> r
            }
        }, onFinished = onFinished)

    fun actions(setup: QuickModelSetup, saver: QuickSaveRunner, currentModel: StateFlow<String?>?): QuickSetupActions =
        QuickSetupActions(
            presets = QuickModelSetup.PRESETS.map {
                QuickPreset(
                    id = it.id, label = it.label, needsBaseUrl = it.id == QuickModelSetup.CUSTOM,
                    keyUrl = it.keyUrl, hint = it.hint, listWithoutKey = it.builtIn,
                )
            },
            currentModel = currentModel,
            loadModels = { id, key, url -> setup.models(id, key, url) },
            test = { id, key, model, url -> setup.test(id, key, model, url).let { QuickCheck(it.verdict == QuickModelSetup.Verdict.OK, it.message) } },
            saver = saver,
        )
}
