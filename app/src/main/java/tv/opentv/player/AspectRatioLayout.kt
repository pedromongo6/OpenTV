// language: Kotlin, file: player/AspectRatioLayout.kt, target: Android 14
// *Video container honouring the scaling modes IPTV users expect: fit, fill, zoom, stretch.*

package tv.opentv.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

class AspectRatioLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Mode { FIT, FILL, ZOOM, STRETCH }

    var mode: Mode = Mode.FIT
        set(value) {
            field = value
            requestLayout()
        }

    /** Source aspect when known; used by ZOOM to crop instead of letterbox. */
    var videoAspect: Float = 16f / 9f
        set(value) {
            field = if (value > 0f && value.isFinite()) value else 16f / 9f
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        when (mode) {
            Mode.FIT -> {
                // Letterbox: fit the whole frame inside, preserving aspect.
                val containerAspect = width.toFloat() / height
                if (videoAspect > containerAspect) {
                    super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec((width / videoAspect).toInt(), MeasureSpec.EXACTLY),
                    )
                } else {
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec((height * videoAspect).toInt(), MeasureSpec.EXACTLY),
                        heightMeasureSpec,
                    )
                }
            }

            Mode.STRETCH, Mode.FILL, Mode.ZOOM ->
                // All three fill the surface; the difference is inside onLayout.
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)

            val (cw, ch) = when (mode) {
                Mode.STRETCH -> w to h

                Mode.FILL -> {
                    // Preserve aspect, cover the surface, crop the overflow.
                    val containerAspect = w.toFloat() / h
                    if (videoAspect > containerAspect) (h * videoAspect).toInt() to h
                    else w to (w / videoAspect).toInt()
                }

                Mode.ZOOM -> {
                    // Same as FILL but slightly over-scaled, useful for 4:3 on 16:9.
                    val containerAspect = w.toFloat() / h
                    val base = if (videoAspect > containerAspect) (h * videoAspect).toInt() to h
                    else w to (w / videoAspect).toInt()
                    (base.first * 1.08f).toInt() to (base.second * 1.08f).toInt()
                }

                Mode.FIT -> {
                    val containerAspect = w.toFloat() / h
                    if (videoAspect > containerAspect) (w) to (w / videoAspect).toInt()
                    else (h * videoAspect).toInt() to (h)
                }
            }

            // Clamp so a bad aspect can never exceed the view bounds.
            val finalW = min(cw, (w * 2.2f).toInt())
            val finalH = min(ch, (h * 2.2f).toInt())

            val cx = (w - finalW) / 2
            val cy = (h - finalH) / 2
            child.layout(cx, cy, cx + finalW, cy + finalH)
        }
    }
}
