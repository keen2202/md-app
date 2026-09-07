package com.moread.app.reader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import com.moread.app.R

/**
 * 本地图片全屏查看 + 双指缩放（PRD §6.4）。
 * ZoomImageView 为轻量自绘 PhotoView，不引入第三方大图库（SPEC §1.3）。
 */
object ImageViewerDialog {

    fun show(context: Context, uri: Uri) {
        val bitmap = decodeSampled(context, uri, 2048, 2048)
        if (bitmap == null) {
            Toast.makeText(context, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = android.app.Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val zoomView = ZoomImageView(context)
        zoomView.setImageBitmap(bitmap)
        zoomView.setBackgroundColor(Color.BLACK)

        val close = android.widget.ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(0x66000000)
            setColorFilter(Color.WHITE)
            contentDescription = context.getString(R.string.cancel)
            val size = (52 * context.resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
            setOnClickListener { dialog.dismiss() }
        }

        val root = FrameLayout(context)
        root.addView(zoomView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(close)

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }
        dialog.setOnDismissListener { bitmap.recycle() }
        dialog.show()
    }

    private fun decodeSampled(context: Context, uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Exception) {
        null
    }
}

/** 支持双指缩放、双击缩放与拖动的轻量图片视图。 */
class ZoomImageView(context: Context) : AppCompatImageView(context) {

    private val matrix = Matrix()
    private var baseMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.coerceIn(0.4f, 4f)
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        },
    )

    init {
        scaleType = ImageView.ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            post {
                baseMatrix.reset()
                val scale = minOf(
                    width.toFloat() / bm.width.toFloat(),
                    height.toFloat() / bm.height.toFloat(),
                    1.8f,
                )
                baseMatrix.setScale(scale, scale)
                baseMatrix.postTranslate(
                    (width - bm.width * scale) / 2f,
                    (height - bm.height * scale) / 2f,
                )
                matrix.set(baseMatrix)
                imageMatrix = matrix
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && event.pointerCount == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    imageMatrix = matrix
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (event.eventTime - downTime < 220) {
                        // 双击：在基础缩放与 2.2x 之间切换。
                        val current = matrixValues()
                        if (current > 1.05f) {
                            matrix.set(baseMatrix)
                        } else {
                            matrix.set(baseMatrix)
                            matrix.postScale(2.2f, 2.2f, width / 2f, height / 2f)
                        }
                        imageMatrix = matrix
                    }
                }
            }
        }
        return true
    }

    private fun matrixValues(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
}
