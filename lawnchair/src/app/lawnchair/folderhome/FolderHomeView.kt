package app.lawnchair.folderhome

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.R
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.WorkspaceData
import com.android.launcher3.util.Executors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.function.Predicate
import kotlin.math.abs

/**
 * Full-screen layer for the Folder Home state: the space hub, and the only home
 * surface of the launcher.
 *
 * The upper zone is a glass-style status card (a live clock for now; the real
 * widget host is wired up in a later unit). The lower zone shows one card per
 * folder in the model under a section header, six cards (2 columns x 3 rows)
 * per page, paging horizontally inside the zone only.
 *
 * There is no gesture out of the hub into the workspace: swiping up opens All
 * Apps through the launcher's own All Apps swipe controller, and everything
 * else returns here.
 *
 * Tapping a card cross-zooms to the folder layer ([FolderPagesView]), which
 * pages through the same folders one per page. The launcher state stays
 * FOLDER_HOME throughout; the two layers are modes of this view, switched by
 * [openFolder] and [closeFolderPage], and the system back action is routed
 * back here by a back-press handler registered by the launcher.
 */
class FolderHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val folderPager = FolderHubPager(context)
    private val emptyLabel = TextView(context)
    private val widgetClock = TextView(context)
    private val widgetDate = TextView(context)
    private val hubCountBadge = TextView(context)
    private val hubDots = LinearLayout(context)
    private val prevPageButton = TextView(context)
    private val nextPageButton = TextView(context)

    private lateinit var hubRoot: LinearLayout
    private val folderPages = FolderPagesView(context)

    private var currentEntries: List<FolderEntry> = emptyList()
    private var totalFolders = 0
    private var refreshQueued = false

    private var openFolderId = NO_FOLDER_ID
    private var layerFraction = 0f
    private var layerAnimator: ValueAnimator? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, delayToNextMinuteMs())
        }
    }

    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun bindCompleteModel(itemIdMap: WorkspaceData, isBindingSync: Boolean) {
            requestRefresh()
        }

        override fun bindItemsAdded(items: List<ItemInfo>) {
            requestRefresh()
        }

        override fun bindItemsUpdated(updates: Set<ItemInfo>) {
            requestRefresh()
        }

        override fun bindWorkspaceComponentsRemoved(matcher: Predicate<ItemInfo?>) {
            requestRefresh()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(BACKGROUND_COLOR)
        contentDescription = context.getString(R.string.folder_home_title)

        hubRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(HORIZONTAL_PADDING_DP),
                dp(TOP_PADDING_DP),
                dp(HORIZONTAL_PADDING_DP),
                dp(BOTTOM_PADDING_DP),
            )
        }

        // Widget zone: a header row (label and decorative page dots) over a
        // glass card. The card shows a live clock for now; real widget hosting
        // replaces its contents in a later unit.
        val widgetZone = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val widgetHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val widgetLabel = TextView(context).apply {
            text = context.getString(R.string.folder_home_widgets_label)
            setTextColor(Color.WHITE)
            alpha = SECONDARY_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_LABEL_TEXT_SIZE_SP)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = LABEL_LETTER_SPACING
        }
        widgetHeader.addView(widgetLabel)
        widgetHeader.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        val widgetDots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(WIDGET_DOT_COUNT) { index ->
            widgetDots.addView(createPageDot(index == 0))
        }
        widgetHeader.addView(widgetDots)
        widgetZone.addView(widgetHeader)

        val widgetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBackground(WIDGET_CARD_CORNER_RADIUS_DP)
            val paddingH = dp(WIDGET_CARD_PADDING_H_DP)
            val paddingV = dp(WIDGET_CARD_PADDING_V_DP)
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }
        widgetClock.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_TEXT_SIZE_SP)
            typeface = Typeface.DEFAULT_BOLD
        }
        widgetCard.addView(widgetClock)
        widgetDate.apply {
            setTextColor(Color.WHITE)
            alpha = DATE_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DATE_TEXT_SIZE_SP)
        }
        widgetCard.addView(
            widgetDate,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(DATE_MARGIN_TOP_DP)
            },
        )
        // The card sizes to its content instead of filling the zone, so the
        // widget zone stays compact and the folder section gets the rest of
        // the screen.
        widgetZone.addView(
            widgetCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(HEADER_MARGIN_TOP_DP)
            },
        )
        hubRoot.addView(
            widgetZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Folder section: a header row (title, space count badge, page arrows
        // and dots) over the paging card grid.
        val folderSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val folderHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hubTitle = TextView(context).apply {
            text = context.getString(R.string.folder_home_hub_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HUB_TITLE_TEXT_SIZE_SP)
            typeface = Typeface.DEFAULT_BOLD
        }
        folderHeader.addView(hubTitle)
        hubCountBadge.apply {
            background = badgeBackground()
            setTextColor(withAlpha(Color.WHITE, BADGE_TEXT_ALPHA))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BADGE_TEXT_SIZE_SP)
            val paddingH = dp(BADGE_PADDING_H_DP)
            val paddingV = dp(BADGE_PADDING_V_DP)
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }
        folderHeader.addView(
            hubCountBadge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(BADGE_MARGIN_START_DP)
            },
        )
        folderHeader.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        configurePageButton(prevPageButton, PREV_PAGE_GLYPH, R.string.folder_home_prev_page) {
            folderPager.goToPage(folderPager.currentPage - 1)
        }
        folderHeader.addView(
            prevPageButton,
            LinearLayout.LayoutParams(dp(PAGE_BUTTON_SIZE_DP), dp(PAGE_BUTTON_SIZE_DP)),
        )
        hubDots.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        folderHeader.addView(
            hubDots,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(DOTS_MARGIN_H_DP)
                marginEnd = dp(DOTS_MARGIN_H_DP)
            },
        )
        configurePageButton(nextPageButton, NEXT_PAGE_GLYPH, R.string.folder_home_next_page) {
            folderPager.goToPage(folderPager.currentPage + 1)
        }
        folderHeader.addView(
            nextPageButton,
            LinearLayout.LayoutParams(dp(PAGE_BUTTON_SIZE_DP), dp(PAGE_BUTTON_SIZE_DP)),
        )
        folderSection.addView(folderHeader)

        val folderZone = FrameLayout(context)
        folderZone.addView(
            folderPager,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        emptyLabel.apply {
            text = context.getString(R.string.folder_home_no_folders)
            setTextColor(Color.WHITE)
            alpha = SECONDARY_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EMPTY_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        folderZone.addView(
            emptyLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
        folderSection.addView(
            folderZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = dp(HEADER_MARGIN_TOP_DP)
            },
        )
        hubRoot.addView(
            folderSection,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                FOLDER_ZONE_WEIGHT,
            ).apply {
                topMargin = dp(ZONE_GAP_DP)
            },
        )

        folderPager.onPageChangeListener = { updateHubHeader() }
        updateHubHeader()
        updateClock()

        addView(hubRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // The folder layer sits on top of the hub and is only revealed by the
        // cross-zoom transition; it starts hidden at its zoomed-out scale.
        folderPages.visibility = View.GONE
        folderPages.alpha = 0f
        folderPages.scaleX = FOLDER_LAYER_START_SCALE
        folderPages.scaleY = FOLDER_LAYER_START_SCALE
        folderPages.onBackRequested = { closeFolderPage() }
        addView(folderPages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherAppState.getInstance(context).model.addCallbacks(modelCallbacks)
        requestRefresh()
        updateClock()
        clockHandler.postDelayed(clockTicker, delayToNextMinuteMs())
    }

    override fun onDetachedFromWindow() {
        clockHandler.removeCallbacks(clockTicker)
        LauncherAppState.getInstance(context).model.removeCallbacks(modelCallbacks)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consume everything not handled by children so touches never leak to the
        // workspace below.
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // While the two layers cross-animate, swallow touches so nothing lands
        // on a half-faded layer.
        return layerAnimator?.isRunning == true
    }

    /** Whether the folder layer is currently open over the hub. */
    fun isInFolderMode(): Boolean {
        return openFolderId != NO_FOLDER_ID
    }

    /**
     * Returns from the folder layer to the hub with the zoom-out transition.
     * Called by the folder layer's back button and by the launcher's
     * back-press handler for the system back action.
     */
    fun closeFolderPage() {
        if (!isInFolderMode()) return
        openFolderId = NO_FOLDER_ID
        // Bring the hub back on the page holding the folder last viewed.
        val lastIndex = folderPages.currentFolderIndex
        if (lastIndex >= 0) {
            folderPager.goToPage(lastIndex / FOLDERS_PER_PAGE)
        }
        animateLayers(opening = false)
    }

    private fun animateLayers(opening: Boolean) {
        val target = if (opening) 1f else 0f
        layerAnimator?.cancel()
        layerAnimator = null
        if (layerFraction == target) {
            applyLayerFraction(target)
            settleLayers(target)
            return
        }
        hubRoot.visibility = View.VISIBLE
        folderPages.visibility = View.VISIBLE
        val animator = ValueAnimator.ofFloat(layerFraction, target)
        animator.duration =
            (LAYER_TRANSITION_DURATION_MS * abs(target - layerFraction)).toLong()
        animator.interpolator = LAYER_TRANSITION_INTERPOLATOR
        animator.addUpdateListener { applyLayerFraction(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (layerAnimator === animation) {
                    layerAnimator = null
                    settleLayers(target)
                }
            }
        })
        layerAnimator = animator
        animator.start()
    }

    private fun applyLayerFraction(fraction: Float) {
        layerFraction = fraction
        hubRoot.scaleX = 1f + HUB_LAYER_ZOOM * fraction
        hubRoot.scaleY = 1f + HUB_LAYER_ZOOM * fraction
        hubRoot.alpha = 1f - fraction
        val folderScale = FOLDER_LAYER_START_SCALE + (1f - FOLDER_LAYER_START_SCALE) * fraction
        folderPages.scaleX = folderScale
        folderPages.scaleY = folderScale
        folderPages.alpha = fraction
    }

    private fun settleLayers(fraction: Float) {
        if (fraction >= 1f) {
            hubRoot.visibility = View.GONE
        } else if (fraction <= 0f) {
            folderPages.visibility = View.GONE
        }
    }

    /** Drops back to the hub with no transition, e.g. when the open folder disappears. */
    private fun resetToHub() {
        layerAnimator?.cancel()
        layerAnimator = null
        openFolderId = NO_FOLDER_ID
        applyLayerFraction(0f)
        hubRoot.visibility = View.VISIBLE
        folderPages.visibility = View.GONE
    }

    private fun requestRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        LauncherAppState.getInstance(context).model.loadAsync { dataModel ->
            if (dataModel == null) {
                Executors.MAIN_EXECUTOR.execute { refreshQueued = false }
                return@loadAsync
            }
            val entries = buildFolderEntries(dataModel)
            Executors.MAIN_EXECUTOR.execute {
                refreshQueued = false
                if (isAttachedToWindow) {
                    applyFolderEntries(entries)
                }
            }
        }
    }

    /** Reads every folder in the model into a display-ready snapshot. */
    private fun buildFolderEntries(dataModel: BgDataModel): List<FolderEntry> {
        synchronized(dataModel) {
            val folders = ArrayList<FolderInfo>()
            dataModel.itemsIdMap.forEach { item ->
                if (item is FolderInfo) {
                    folders.add(item)
                }
            }
            return folders.sortedWith(FOLDER_ORDER).map { info ->
                val contents = info.getContents()
                FolderEntry(
                    info = info,
                    title = info.title?.toString()?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.folder_home_unnamed_folder),
                    appCount = contents.size,
                    previewItems = contents.mapNotNull { it as? ItemInfoWithIcon }
                        .take(PREVIEW_ITEM_COUNT),
                    apps = contents,
                    accentColor = ACCENT_COLORS[Math.floorMod(info.id, ACCENT_COLORS.size)],
                )
            }
        }
    }

    private fun applyFolderEntries(entries: List<FolderEntry>) {
        currentEntries = entries
        totalFolders = entries.size
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val pages = entries.chunked(FOLDERS_PER_PAGE).map { pageEntries ->
            createFolderPage(pageEntries)
        }
        folderPager.setPages(pages, folderPager.currentPage)
        folderPages.setFolders(entries)
        if (isInFolderMode()) {
            // Keep the folder layer on the same folder after a model change;
            // if that folder is gone, fall back to the hub.
            val openIndex = entries.indexOfFirst { it.info.id == openFolderId }
            if (openIndex >= 0) {
                folderPages.showFolder(openIndex)
            } else {
                resetToHub()
            }
        }
        updateHubHeader()
    }

    private fun createFolderPage(entries: List<FolderEntry>): View {
        val grid = GridLayout(context).apply {
            columnCount = GRID_COLUMNS
            rowCount = GRID_ROWS
        }
        // Every cell of the 2 x 3 grid is always added, with an empty spacer
        // where the page has no folder. GridLayout only distributes its extra
        // space over rows and columns that hold a weighted child, so without
        // the spacers a lone card would stretch over the whole page.
        for (index in 0 until FOLDERS_PER_PAGE) {
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / GRID_COLUMNS, GridLayout.FILL, 1f),
                GridLayout.spec(index % GRID_COLUMNS, GridLayout.FILL, 1f),
            ).apply {
                width = 0
                height = 0
                val margin = dp(CARD_MARGIN_DP)
                setMargins(margin, margin, margin, margin)
            }
            val cell = if (index < entries.size) {
                createFolderCard(entries[index])
            } else {
                View(context)
            }
            grid.addView(cell, params)
        }
        return grid
    }

    private fun createFolderCard(entry: FolderEntry): View {
        val card = FrameLayout(context).apply {
            background = cardBackground(entry.accentColor)
            val padding = dp(CARD_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            isClickable = true
            setOnClickListener { openFolder(entry.info.id) }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top row: the first apps in a single horizontal row, accent dot at the end.
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entry.previewItems.forEachIndexed { index, item ->
            val iconView = ImageView(context).apply {
                setImageDrawable(item.newIcon(context))
            }
            val params = LinearLayout.LayoutParams(
                dp(PREVIEW_ICON_SIZE_DP),
                dp(PREVIEW_ICON_SIZE_DP),
            )
            if (index > 0) {
                params.marginStart = dp(PREVIEW_ICON_MARGIN_DP)
            }
            topRow.addView(iconView, params)
        }
        topRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        val accentDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(entry.accentColor)
            }
        }
        topRow.addView(
            accentDot,
            LinearLayout.LayoutParams(dp(ACCENT_DOT_SIZE_DP), dp(ACCENT_DOT_SIZE_DP)),
        )
        content.addView(topRow)
        content.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        val name = TextView(context).apply {
            text = entry.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FOLDER_NAME_TEXT_SIZE_SP)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(name)

        // Bottom row: app count on the left, chevron on the right.
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val count = TextView(context).apply {
            text = resources.getQuantityString(
                R.plurals.folder_home_app_count,
                entry.appCount,
                entry.appCount,
            )
            setTextColor(Color.WHITE)
            alpha = SECONDARY_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, APP_COUNT_TEXT_SIZE_SP)
        }
        bottomRow.addView(count)
        bottomRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        val chevron = TextView(context).apply {
            text = CHEVRON_GLYPH
            setTextColor(Color.WHITE)
            alpha = SECONDARY_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CHEVRON_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
        }
        bottomRow.addView(chevron)
        content.addView(
            bottomRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(CARD_TEXT_MARGIN_DP)
            },
        )
        card.addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        return card
    }

    /**
     * Opens the folder layer on the folder with [folderId], cross-zooming from
     * the hub. Does nothing when there is no such folder (in particular when
     * there are no folders at all).
     */
    private fun openFolder(folderId: Int) {
        val index = currentEntries.indexOfFirst { it.info.id == folderId }
        if (index < 0) return
        openFolderId = folderId
        folderPages.showFolder(index)
        animateLayers(opening = true)
    }

    /** Refreshes the section header: space count, page dots and arrow states. */
    private fun updateHubHeader() {
        hubCountBadge.text = resources.getString(R.string.folder_home_space_count, totalFolders)
        val pageCount = folderPager.pageCount
        val current = folderPager.currentPage
        hubDots.removeAllViews()
        for (index in 0 until pageCount) {
            hubDots.addView(createPageDot(index == current))
        }
        val hasPrev = current > 0
        prevPageButton.isEnabled = hasPrev
        prevPageButton.alpha = if (hasPrev) 1f else DISABLED_BUTTON_ALPHA
        val hasNext = current < pageCount - 1
        nextPageButton.isEnabled = hasNext
        nextPageButton.alpha = if (hasNext) 1f else DISABLED_BUTTON_ALPHA
    }

    private fun configurePageButton(
        button: TextView,
        glyph: String,
        descriptionRes: Int,
        onClick: () -> Unit,
    ) {
        button.apply {
            text = glyph
            contentDescription = context.getString(descriptionRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PAGE_BUTTON_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            background = circleBackground()
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** A page dot: elongated and bright for the current page, small and dim otherwise. */
    private fun createPageDot(active: Boolean): View {
        val dot = View(context)
        val widthDp = if (active) DOT_ACTIVE_WIDTH_DP else DOT_SIZE_DP
        dot.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(DOT_SIZE_DP)).apply {
            val margin = dp(DOT_MARGIN_DP)
            setMargins(margin, 0, margin, 0)
        }
        dot.background = GradientDrawable().apply {
            cornerRadius = dp(DOT_SIZE_DP).toFloat() / 2
            setColor(
                if (active) {
                    Color.WHITE
                } else {
                    withAlpha(Color.WHITE, DOT_INACTIVE_ALPHA)
                },
            )
        }
        return dot
    }

    private fun updateClock() {
        val now = Date()
        val locale = Locale.getDefault()
        widgetClock.text = SimpleDateFormat(CLOCK_PATTERN, locale).format(now)
        val datePattern = if (locale.language == Locale.JAPANESE.language) {
            DATE_PATTERN_JA
        } else {
            DATE_PATTERN_DEFAULT
        }
        widgetDate.text = SimpleDateFormat(datePattern, locale).format(now)
    }

    private fun delayToNextMinuteMs(): Long {
        val calendar = Calendar.getInstance()
        val millisIntoMinute =
            calendar.get(Calendar.SECOND) * MILLIS_IN_SECOND + calendar.get(Calendar.MILLISECOND)
        return (MILLIS_IN_MINUTE - millisIntoMinute).toLong()
    }

    private fun cardBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(withAlpha(accentColor, CARD_FILL_ALPHA))
            setStroke(dp(1), withAlpha(Color.WHITE, CARD_STROKE_ALPHA))
            cornerRadius = dp(CARD_CORNER_RADIUS_DP).toFloat()
        }
    }

    private fun glassBackground(cornerRadiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(withAlpha(Color.WHITE, GLASS_FILL_ALPHA))
            setStroke(dp(1), withAlpha(Color.WHITE, GLASS_STROKE_ALPHA))
            cornerRadius = dp(cornerRadiusDp).toFloat()
        }
    }

    private fun badgeBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(withAlpha(Color.WHITE, BADGE_FILL_ALPHA))
            cornerRadius = dp(BADGE_CORNER_RADIUS_DP).toFloat()
        }
    }

    private fun circleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(Color.WHITE, PAGE_BUTTON_FILL_ALPHA))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val BACKGROUND_COLOR = 0xF220202A.toInt()

        private const val NO_FOLDER_ID = -1
        private const val LAYER_TRANSITION_DURATION_MS = 420L
        private const val HUB_LAYER_ZOOM = 0.25f
        private const val FOLDER_LAYER_START_SCALE = 0.82f

        // cubic-bezier(0.22, 1, 0.36, 1), the prototype's layer easing.
        private val LAYER_TRANSITION_INTERPOLATOR = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        private const val HORIZONTAL_PADDING_DP = 20
        private const val TOP_PADDING_DP = 28
        private const val BOTTOM_PADDING_DP = 20
        private const val ZONE_GAP_DP = 16
        private const val FOLDER_ZONE_WEIGHT = 1f

        private const val GRID_COLUMNS = 2
        private const val GRID_ROWS = 3
        private const val FOLDERS_PER_PAGE = GRID_COLUMNS * GRID_ROWS
        private const val CARD_MARGIN_DP = 6
        private const val CARD_PADDING_DP = 14
        private const val CARD_TEXT_MARGIN_DP = 4
        private const val CARD_CORNER_RADIUS_DP = 24
        private const val CARD_FILL_ALPHA = 0x40
        private const val CARD_STROKE_ALPHA = 0x33

        private const val PREVIEW_ITEM_COUNT = 4
        private const val PREVIEW_ICON_SIZE_DP = 30
        private const val PREVIEW_ICON_MARGIN_DP = 6
        private const val ACCENT_DOT_SIZE_DP = 10
        private const val CHEVRON_TEXT_SIZE_SP = 18f
        private const val CHEVRON_GLYPH = "›"

        private const val HEADER_MARGIN_TOP_DP = 10
        private const val SECTION_LABEL_TEXT_SIZE_SP = 11f
        private const val LABEL_LETTER_SPACING = 0.12f
        private const val WIDGET_DOT_COUNT = 3
        private const val WIDGET_CARD_CORNER_RADIUS_DP = 26
        private const val WIDGET_CARD_PADDING_H_DP = 20
        private const val WIDGET_CARD_PADDING_V_DP = 16
        private const val GLASS_FILL_ALPHA = 0x1F
        private const val GLASS_STROKE_ALPHA = 0x2E
        private const val CLOCK_TEXT_SIZE_SP = 40f
        private const val CLOCK_PATTERN = "HH:mm"
        private const val DATE_TEXT_SIZE_SP = 13f
        private const val DATE_TEXT_ALPHA = 0.75f
        private const val DATE_MARGIN_TOP_DP = 2
        private const val DATE_PATTERN_JA = "M月d日 (E)"
        private const val DATE_PATTERN_DEFAULT = "EEE, MMM d"
        private const val MILLIS_IN_MINUTE = 60_000
        private const val MILLIS_IN_SECOND = 1_000

        private const val HUB_TITLE_TEXT_SIZE_SP = 16f
        private const val BADGE_TEXT_SIZE_SP = 10f
        private const val BADGE_TEXT_ALPHA = 0xD9
        private const val BADGE_FILL_ALPHA = 0x26
        private const val BADGE_CORNER_RADIUS_DP = 10
        private const val BADGE_PADDING_H_DP = 8
        private const val BADGE_PADDING_V_DP = 2
        private const val BADGE_MARGIN_START_DP = 8
        private const val PAGE_BUTTON_SIZE_DP = 28
        private const val PAGE_BUTTON_TEXT_SIZE_SP = 20f
        private const val PAGE_BUTTON_FILL_ALPHA = 0x1A
        private const val DISABLED_BUTTON_ALPHA = 0.35f
        private const val PREV_PAGE_GLYPH = "‹"
        private const val NEXT_PAGE_GLYPH = "›"
        private const val DOT_SIZE_DP = 6
        private const val DOT_ACTIVE_WIDTH_DP = 16
        private const val DOT_MARGIN_DP = 2
        private const val DOT_INACTIVE_ALPHA = 0x59
        private const val DOTS_MARGIN_H_DP = 4

        private const val EMPTY_TEXT_SIZE_SP = 15f
        private const val FOLDER_NAME_TEXT_SIZE_SP = 15f
        private const val APP_COUNT_TEXT_SIZE_SP = 11f
        private const val SECONDARY_TEXT_ALPHA = 0.7f

        private val ACCENT_COLORS = intArrayOf(
            0xFFEF5350.toInt(),
            0xFFAB47BC.toInt(),
            0xFF5C6BC0.toInt(),
            0xFF29B6F6.toInt(),
            0xFF26A69A.toInt(),
            0xFF9CCC65.toInt(),
            0xFFFFCA28.toInt(),
            0xFFFF7043.toInt(),
        )

        /** Workspace position order (screen, then row, then column), then title. */
        private val FOLDER_ORDER: Comparator<FolderInfo> = compareBy(
            { info: FolderInfo -> containerRank(info.container) },
            { info: FolderInfo -> info.screenId },
            { info: FolderInfo -> info.cellY },
            { info: FolderInfo -> info.cellX },
            { info: FolderInfo -> info.title?.toString()?.lowercase() ?: "" },
            { info: FolderInfo -> info.id },
        )

        private fun containerRank(container: Int): Int {
            return when (container) {
                CONTAINER_DESKTOP -> 0
                CONTAINER_HOTSEAT -> 1
                else -> 2
            }
        }
    }
}
