package app.lawnchair.folderhome

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.util.FloatProperty
import android.view.View
import com.android.app.animation.Interpolators.DECELERATE_1_7
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.anim.PendingAnimation
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.states.StateAnimationConfig

/**
 * Drives the [FolderHomeView] layer in and out based on the launcher state, following the
 * same single-progress pattern as AllAppsTransitionController.
 *
 * Progress 0 means the folder home layer fully covers the workspace; progress 1 means it
 * is fully hidden below the screen.
 */
class FolderHomeTransitionController(
    private val launcher: Launcher,
) : StateManager.StateHandler<LauncherState> {

    private var folderHomeView: FolderHomeView? = null

    private var progress = PROGRESS_HIDDEN

    fun setupViews(view: FolderHomeView) {
        folderHomeView = view
        setProgress(progress)
    }

    fun setProgress(value: Float) {
        progress = value
        val view = folderHomeView ?: return
        val shiftRange = if (view.height > 0) {
            view.height.toFloat()
        } else {
            launcher.deviceProfile.deviceProperties.heightPx.toFloat()
        }
        view.translationY = progress * shiftRange
        view.visibility = if (progress >= PROGRESS_HIDDEN) View.GONE else View.VISIBLE
    }

    fun getProgress(): Float = progress

    override fun setState(state: LauncherState) {
        setProgress(targetProgress(state))
    }

    override fun setStateWithAnimation(
        toState: LauncherState,
        config: StateAnimationConfig,
        builder: PendingAnimation,
    ) {
        val target = targetProgress(toState)
        if (progress == target) {
            return
        }
        val anim = ObjectAnimator.ofFloat(this, PROGRESS_PROPERTY, progress, target)
        anim.interpolator = config.getInterpolator(
            StateAnimationConfig.ANIM_VERTICAL_PROGRESS,
            DECELERATE_1_7,
        )
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                setProgress(target)
            }
        })
        builder.add(anim)
    }

    private fun targetProgress(state: LauncherState): Float {
        return if (state == LauncherState.FOLDER_HOME) PROGRESS_SHOWN else PROGRESS_HIDDEN
    }

    companion object {
        private const val PROGRESS_SHOWN = 0f
        private const val PROGRESS_HIDDEN = 1f

        val PROGRESS_PROPERTY =
            object : FloatProperty<FolderHomeTransitionController>("folderHomeProgress") {
                override fun get(controller: FolderHomeTransitionController): Float {
                    return controller.getProgress()
                }

                override fun setValue(controller: FolderHomeTransitionController, value: Float) {
                    controller.setProgress(value)
                }
            }
    }
}
