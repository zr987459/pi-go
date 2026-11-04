package io.github.zr987459.pigo.di

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.core.session.PiActionsImpl
import io.github.zr987459.pigo.core.session.PiSettingsImpl
import io.github.zr987459.pigo.core.session.QueueRecall
import io.github.zr987459.pigo.core.session.SessionCatalogImpl
import io.github.zr987459.pigo.core.session.SessionHostImpl
import io.github.zr987459.pigo.core.session.SessionOps
import io.github.zr987459.pigo.feature.chat.ChatController
import io.github.zr987459.pigo.feature.chat.ChatControllerPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 两种形态共用的 [SessionHost]（DESIGN-r3 §5.1）。AppGraph 持有单例，与进程同寿。
 *
 * - REAL：`SessionHostImpl`（core/session）。**同一个** engine / extras（同一个 PiEngine，引擎只起一个）；
 *   草稿、发送（Outbox）、扩展对话框、队列退回都经 [ChatControllerPort] 复用聊天控制器那一份状态；
 *   动作表 `PiActionsImpl` 注入 [ModelPanels]（RealModelPanels）与 [SessionOps]（[AppSessionOps]）。
 * - FAKE：`FakeSessionHost`（core/contract），只给开发期与 A 档切换测试用。
 */
object SessionBindings {

    /** 装配 REAL 宿主需要的依赖（全部来自 AppGraph 的单例）。 */
    class Deps(
        val engine: EngineClient,
        val extras: EngineExtras,
        val controller: ChatController,
        val settings: PiSettings,
        val repo: SessionRepository,
        val panels: ModelPanels?,
        val ops: SessionOps,
        val imageLoader: suspend (List<DraftImage>) -> List<ImageInput> = { emptyList() },
        val pid: () -> Int? = { null },
    )

    fun host(mode: Mode, ctx: BindingContext, initialForm: UiForm, deps: () -> Deps): SessionHost =
        when (mode) {
            Mode.FAKE -> fake(ctx, initialForm)
            Mode.REAL -> real(ctx.appScope, initialForm, deps())
        }

    fun fake(ctx: BindingContext, initialForm: UiForm): SessionHost =
        FakeSessionHost(ctx.appScope, initialForm = initialForm)

    /** 真实宿主。抽成纯 Kotlin（不碰 Android），JVM 测试用 Fake 引擎直接装配同一段代码。 */
    fun real(scope: CoroutineScope, initialForm: UiForm, d: Deps): SessionHostImpl {
        var hostRef: SessionHostImpl? = null
        val catalog = SessionCatalogImpl(
            repo = d.repo,
            engine = d.engine,
            scope = scope,
            onEphemeralChanged = { on -> hostRef?.setEphemeral(on) },
        )
        return SessionHostImpl(
            engine = d.engine,
            extras = d.extras,
            scope = scope,
            settings = d.settings,
            sessions = catalog,
            chatPort = ChatControllerPort(d.controller, d.imageLoader),
            initialForm = initialForm,
            pid = d.pid,
            actionsFactory = { h -> PiActionsImpl(h, d.ops, d.panels) },
        ).also { h ->
            hostRef = h
            // `/quit` 撤回的排队文本并进共享草稿（两种形态同一份），不丢。
            (d.ops as? AppSessionOps)?.onWithdrawn = { texts -> h.draft.value = QueueRecall.merge(h.draft.value, texts) }
        }
    }

    /** pi 的 settings.json（容器内 `~/.pi/agent/settings.json`）。写完后经 `/reload`（pigo-bridge `/pigo:reload`）生效。 */
    fun piSettings(runtime: RuntimeManager): PiSettingsImpl =
        PiSettingsImpl(file = runtime.hostFile("/root/.pi/agent/settings.json"))

    /** 草稿里的图片 URI → base64（发给 pi 的 images）。单张上限 [MAX_IMAGE_BYTES]，读不到的跳过。 */
    fun imageLoader(context: Context): suspend (List<DraftImage>) -> List<ImageInput> = { list ->
        withContext(Dispatchers.IO) {
            list.mapNotNull { img ->
                runCatching {
                    val uri = Uri.parse(img.uri)
                    val cr = context.contentResolver
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    if (bytes.size > MAX_IMAGE_BYTES) return@runCatching null
                    val mime = img.mimeType ?: cr.getType(uri) ?: "image/png"
                    ImageInput(mimeType = mime, base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.getOrNull()
            }
        }
    }

    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
}
