package com.example.camapp

import android.graphics.*
import android.graphics.drawable.Drawable

class BoundaryDrawable(private val boundaryRect: Rect) : Drawable() {

    private val zoneBoundaryPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLUE
        strokeWidth = 5F
        alpha = 255
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(
            boundaryRect,
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