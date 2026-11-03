package io.github.zr987459.pigo

import android.app.Application

class PiGoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 系统分享入口（feature/files 的 ShareActivity）冷启动时也要拿到工作区依赖。
        runCatching { graph.workspace.deps }
    }
}
