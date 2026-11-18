package com.aravind.wallshift

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout that is always phone-shaped (9:16), based on its width. */
class AspectFrameLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(w * 16 / 9, MeasureSpec.EXACTLY)
        )
    }
}
