package io.github.zr987459.pigo.core.catalog

import android.content.Context
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.core.store.GuestPaths
import io.github.zr987459.pigo.core.store.ModelPrefsStore
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * `:core:catalog` 的真实实现装配（供 `app/di/ModelsBindings.kt` 在 W5 接线时使用）。
 * 与 `StoreBindings` 共用同一个 [PiProviderConfigStore] 实例（它同时是 LaunchEnvProvider）。
 */
class CatalogGraph(
    context: Context,
    runtime: RuntimeManager,
    secrets: SecretStore,
    val config: PiProviderConfigStore,
    scope: CoroutineScope,
    runtimeModels: suspend () -> List<ModelInfo>?,
    onCredentialsChanged: suspend (String) -> Unit,
) {
    private val app = context.applicationContext
    val paths = GuestPaths(File(app.filesDir, "rootfs"))
    val scripts = GuestScripts(runtime) { app.assets.open(it) }
    /** 模型偏好放宿主私有目录（noBackupFilesDir：不进系统备份）。 */
    val prefs = ModelPrefsStore(File(app.noBackupFilesDir, "pigo/model-prefs.json"))
    val repo = CatalogRepository(runtime, scripts, File(app.noBackupFilesDir, "pigo/catalog"))
    val authService = AuthService(runtime, scripts, config, paths, scope, onCredentialsChanged = onCredentialsChanged)
    val auth = RealAuthManager(config, authService)
    val catalog = RealModelCatalog(repo, config, authService, prefs, runtimeModels)
    val custom = RealCustomProviderStore(config, secrets, runtime)
    val settings = RealAppSettingsStore(config, prefs, scope)
}
