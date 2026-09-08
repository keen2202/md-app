package com.moread.app

import android.app.Application
import com.moread.app.log.AppLog
import com.moread.app.theme.ThemeEngine

/**
 * Application 壳（SPEC §1.1）。
 * onCreate 仅同步读取轻量设置，禁止任何重活；阅读核心组件全部懒初始化。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 临时排查日志：捕获未处理异常并记录打开 Markdown 的关键阶段。
        AppLog.init(this)
        AppLog.i("App", "onCreate")
        ThemeEngine.init(this)
    }
}
