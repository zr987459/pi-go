package io.github.zr987459.pigo.feature.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 与 activity-compose 的 BackHandler 等价的最小实现（本模块只依赖 androidx.activity，不依赖 activity-compose）。
 * enabled 为 false 时不拦截，返回键照常交给导航/系统。
 */
@Composable
internal fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = current.value()
        }
    }
    SideEffect { callback.isEnabled = enabled }
    val owner = LocalContext.current.findBackOwner() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(owner, lifecycleOwner) {
        owner.onBackPressedDispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findBackOwner(): OnBackPressedDispatcherOwner? = when (this) {
    is OnBackPressedDispatcherOwner -> this
    is ContextWrapper -> baseContext.findBackOwner()
    else -> null
}
