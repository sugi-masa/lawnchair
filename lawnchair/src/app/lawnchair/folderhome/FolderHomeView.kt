package app.lawnchair.folderhome

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import kotlin.math.abs

/**
 * Full-screen layer for the Folder Home state.
 *
 * For now this is a placeholder surface: it covers the workspace so the layer is visible,
 * blocks touches to the workspace underneath, and leaves the state on a downward swipe.
 * Real folder content is added in a later unit.
 */
class FolderHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val swipeThresholdPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        SWIPE_THRESHOLD_DP,
        resources.displayMetrics,
    )

    private var downX = 0f
    private var downY = 0f
    private var swipeTriggered = false

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(BACKGROUND_COLOR)
        contentDescription = context.getString(R.string.folder_home_title)

        val labelLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.folder_home_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.folder_home_swipe_down_hint)
            setTextColor(Color.WHITE)
            alpha = HINT_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
        }
        labelLayout.addView(title)
        labelLayout.addView(hint)
        addView(
            labelLayout,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swipeTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeTriggered) {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    if (dy > swipeThresholdPx && dy > VERTICAL_DOMINANCE_FACTOR * abs(dx)) {
                        swipeTriggered = true
                        goToNormalHome()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeTriggered = false
        }
        // Consume everything so touches never leak to the workspace below.
        return true
    }

    private fun goToNormalHome() {
        val launcher = Launcher.getLauncher(context)
        if (launcher.stateManager.state == LauncherState.FOLDER_HOME) {
            launcher.stateManager.goToState(LauncherState.NORMAL)
        }
    }

    companion object {
        private const val BACKGROUND_COLOR = 0xF220202A.toInt()
        private const val SWIPE_THRESHOLD_DP = 72f
        private const val VERTICAL_DOMINANCE_FACTOR = 2f
        private const val TITLE_TEXT_SIZE_SP = 28f
        private const val HINT_TEXT_SIZE_SP = 14f
        private const val HINT_TEXT_ALPHA = 0.7f
    }
}
