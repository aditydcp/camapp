package com.example.camapp

import android.graphics.*
import android.graphics.drawable.Drawable

class BoundaryDrawable(screenSize: Point) : Drawable() {
    private val zoneBoundaryPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 20F
        alpha = 255
    }

    private val screenSize = screenSize

    override fun draw(canvas: Canvas) {
        canvas.drawRect(
            Rect(
                100,
                250,
                screenSize.x - 100,
                screenSize.y - 250
            ),
            zoneBoundaryPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        zoneBoundaryPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        zoneBoundaryPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java",
        ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat")
    )
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

}