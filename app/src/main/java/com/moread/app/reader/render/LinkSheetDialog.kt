package com.moread.app.reader.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.moread.app.R
import com.moread.app.theme.ThemeEngine

/**
 * 链接底部弹层（PRD §6.4）。
 * 无网络权限，因此只提供「复制链接」；复制后引导用户到浏览器打开。
 */
object LinkSheetDialog {

    fun show(context: Context, url: String) {
        val palette = ThemeEngine.palette()
        val dialog = android.app.Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(palette.background)
        }

        root.addView(TextView(context).apply {
            text = context.getString(R.string.link_copy)
            setTextColor(palette.textPrimary)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text = url
            setTextColor(palette.accent)
            textSize = 15f
            setPadding(0, dp(10), 0, dp(14))
            setTextIsSelectable(true)
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.link_copy)
            setTextColor(palette.background)
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(palette.accent)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                copyText(context, url)
                dialog.dismiss()
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
        })

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        dialog.setOnCancelListener { }
        dialog.show()
    }

    private fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
    }

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
