package com.local.tasknotescompanion

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView
import kotlin.math.min

class RichReminderVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : VideoView(context, attrs) {
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val scale = min(maxWidth.toFloat() / sourceWidth, maxHeight.toFloat() / sourceHeight)
        setMeasuredDimension((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt())
    }
}
