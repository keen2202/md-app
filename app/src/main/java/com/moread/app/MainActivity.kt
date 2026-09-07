package com.moread.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.moread.app.theme.ThemeApplier
import com.moread.app.theme.ThemeEngine
import com.moread.app.ui.home.HomeFragment
import com.moread.app.ui.settings.SettingsFragment

/**
 * 单 Activity 架构：承载首页与设置页（SPEC §1.1）。
 * 阅读页为独立 ReaderActivity；uiMode 变化由本 Activity 与 ReaderActivity 各自处理。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ThemeApplier.applyWindow(this, ThemeEngine.palette())
        if (savedInstanceState == null) {
            show(HomeFragment(), addToBackStack = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeEngine.onSystemConfigurationChanged(newConfig)
        ThemeApplier.applyWindow(this, ThemeEngine.palette())
    }

    fun showSettings() {
        show(SettingsFragment(), addToBackStack = true)
    }

    fun backToHome() {
        if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
    }

    private fun show(fragment: Fragment, addToBackStack: Boolean) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}
