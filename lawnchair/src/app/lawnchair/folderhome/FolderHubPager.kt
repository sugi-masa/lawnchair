package app.lawnchair.folderhome

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Horizontal pager for the folder hub's folder zone. Each child is one full page of
 * folder cards. Only this zone pages horizontally, so it never fights with the
 * vertical gestures owned by the launcher's own touch controllers.
 */
class FolderHubPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    var currentPage = 0
        private set

    /** Replaces all pages, keeping the visible page as close as possible to [page]. */
    fun setPages(pages: List<View>, page: Int) {
        removeAllViews()
        pages.forEach { addView(it) }
        currentPage = page.coerceIn(0, maxOf(0, pages.size - 1))
        if (width > 0) {
            scroller.forceFinished(true)
            scrollTo(currentPage * width, 0)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidth, childHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pageWidth = r - l
        val pageHeight = b - t
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(i * pageWidth, 0, (i + 1) * pageWidth, pageHeight)
        }
        if (changed && !dragging && scroller.isFinished) {
            scrollTo(currentPage * pageWidth, 0)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                obtainVelocityTracker(event)
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                if (!scroller.isFinished) {
                    // A touch while the pager is settling grabs the gesture to stop it.
                    scroller.abortAnimation()
                    dragging = true
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                obtainVelocityTracker(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    lastX = event.x
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                releaseVelocityTracker()
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        obtainVelocityTracker(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                    dragging = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        lastX = event.x
                    }
                }
                if (dragging) {
                    val delta = (lastX - event.x).roundToInt()
                    lastX = event.x
                    scrollBy(delta, 0)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    settleOnRelease()
                }
                dragging = false
                releaseVelocityTracker()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging && width > 0) {
                    snapToPage((scrollX.toFloat() / width).roundToInt())
                }
                dragging = false
                releaseVelocityTracker()
            }
        }
        return true
    }

    override fun scrollTo(x: Int, y: Int) {
        val maxScroll = maxOf(0, (childCount - 1) * width)
        super.scrollTo(x.coerceIn(0, maxScroll), 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    private fun settleOnRelease() {
        val pageWidth = width
        if (pageWidth == 0) return
        velocityTracker?.computeCurrentVelocity(VELOCITY_UNITS, maxFlingVelocity.toFloat())
        val velocityX = velocityTracker?.xVelocity ?: 0f
        val position = scrollX.toFloat() / pageWidth
        val target = when {
            velocityX < -minFlingVelocity -> floor(position).toInt() + 1
            velocityX > minFlingVelocity -> ceil(position).toInt() - 1
            else -> position.roundToInt()
        }
        snapToPage(target)
    }

    private fun snapToPage(page: Int) {
        val pageWidth = width
        if (pageWidth == 0) return
        val target = page.coerceIn(0, maxOf(0, childCount - 1))
        currentPage = target
        val targetX = target * pageWidth
        if (scrollX != targetX) {
            scroller.startScroll(scrollX, 0, targetX - scrollX, 0, SETTLE_DURATION_MS)
            postInvalidateOnAnimation()
        }
    }

    private fun obtainVelocityTracker(event: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    companion object {
        private const val VELOCITY_UNITS = 1000
        private const val SETTLE_DURATION_MS = 350
    }
}
