package app.phrasekeys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Раскладывает плашки слева направо с переносом на новую строку. */
class FlowLayout(context: Context) : ViewGroup(context) {

    private var hGap = 0
    private var vGap = 0

    fun setSpacing(horizontal: Int, vertical: Int) {
        hGap = horizontal
        vGap = vertical
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxLine = width - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(maxLine, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > maxLine) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            x += cw + hGap
            lineHeight = max(lineHeight, ch)
        }

        val total = y + lineHeight + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(total, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxLine = (r - l) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > maxLine) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            child.layout(left, top, left + cw, top + ch)
            x += cw + hGap
            lineHeight = max(lineHeight, ch)
        }
    }
}
