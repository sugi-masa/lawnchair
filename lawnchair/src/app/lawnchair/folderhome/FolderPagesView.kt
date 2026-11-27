package app.lawnchair.folderhome

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.touch.ItemClickHandler

/** Display-ready snapshot of one folder, shared by the hub cards and the folder pages. */
data class FolderEntry(
    val info: FolderInfo,
    val title: String,
    val appCount: Int,
    val previewItems: List<ItemInfoWithIcon>,
    val apps: List<ItemInfo>,
    val accentColor: Int,
)

/**
 * The lower layer of the folder home: one page per folder, paged horizontally.
 *
 * A fixed header carries a back button to the hub, the current folder's name and
 * previous/next folder buttons. Below it, a row of pill tabs jumps straight to
 * any folder and follows the current page. Each page shows the folder's apps in
 * a four-column grid that scrolls vertically; horizontal paging and vertical
 * scrolling stay apart because the pager only intercepts gestures that are
 * mostly horizontal, leaving the rest to the page's scroll view.
 */
class FolderPagesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val folderNameLabel = TextView(context)
    private val prevFolderButton = TextView(context)
    private val nextFolderButton = TextView(context)
    private val tabsScroll = HorizontalScrollView(context)
    private val tabsRow = LinearLayout(context)
    private val pager = FolderHubPager(context)

    private var entries: List<FolderEntry> = emptyList()
    private var tabViews: List<TextView> = emptyList()

    /** Called when the header back button is tapped. */
    var onBackRequested: (() -> Unit)? = null

    /** Index of the folder currently shown, or -1 when there are no folders. */
    val currentFolderIndex: Int
        get() = if (entries.isEmpty()) -1 else pager.currentPage

    init {
        orientation = VERTICAL
        setPadding(
            dp(HORIZONTAL_PADDING_DP),
            dp(TOP_PADDING_DP),
            dp(HORIZONTAL_PADDING_DP),
            dp(BOTTOM_PADDING_DP),
        )

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = TextView(context).apply {
            text = context.getString(R.string.folder_home_back_to_spaces)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BACK_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            background = pillBackground(withAlpha(Color.WHITE, BACK_FILL_ALPHA))
            val paddingH = dp(BACK_PADDING_H_DP)
            val paddingV = dp(BACK_PADDING_V_DP)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            isClickable = true
            setOnClickListener { onBackRequested?.invoke() }
        }
        header.addView(backButton)
        folderNameLabel.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FOLDER_NAME_TEXT_SIZE_SP)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(
            folderNameLabel,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(NAME_MARGIN_START_DP)
                marginEnd = dp(NAME_MARGIN_END_DP)
            },
        )
        configureNavButton(prevFolderButton, PREV_GLYPH, R.string.folder_home_prev_folder) {
            pager.goToPage(pager.currentPage - 1)
        }
        header.addView(
            prevFolderButton,
            LayoutParams(dp(NAV_BUTTON_SIZE_DP), dp(NAV_BUTTON_SIZE_DP)),
        )
        configureNavButton(nextFolderButton, NEXT_GLYPH, R.string.folder_home_next_folder) {
            pager.goToPage(pager.currentPage + 1)
        }
        header.addView(
            nextFolderButton,
            LayoutParams(dp(NAV_BUTTON_SIZE_DP), dp(NAV_BUTTON_SIZE_DP)).apply {
                marginStart = dp(NAV_BUTTON_MARGIN_DP)
            },
        )
        addView(header)

        tabsScroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        tabsRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsScroll.addView(
            tabsRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            tabsScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(TABS_MARGIN_TOP_DP)
            },
        )

        pager.onPageChangeListener = { updateHeaderAndTabs() }
        addView(
            pager,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(PAGER_MARGIN_TOP_DP)
            },
        )
    }

    /** Rebuilds pages and tabs from [newEntries], keeping the shown folder when possible. */
    fun setFolders(newEntries: List<FolderEntry>) {
        entries = newEntries
        // Tabs first: the pager's page-change listener syncs against them, so
        // they must already match the new entries when the pages are swapped.
        rebuildTabs()
        pager.setPages(entries.map { createAppPage(it) }, pager.currentPage)
        updateHeaderAndTabs()
    }

    /** Shows the folder at [index] immediately, without the pager settle animation. */
    fun showFolder(index: Int) {
        if (entries.isEmpty()) return
        pager.jumpToPage(index)
        updateHeaderAndTabs()
    }

    private fun rebuildTabs() {
        tabsRow.removeAllViews()
        tabViews = entries.mapIndexed { index, entry ->
            val tab = TextView(context).apply {
                text = entry.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE_SP)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                val paddingH = dp(TAB_PADDING_H_DP)
                val paddingV = dp(TAB_PADDING_V_DP)
                setPadding(paddingH, paddingV, paddingH, paddingV)
                isClickable = true
                setOnClickListener { pager.goToPage(index) }
            }
            tabsRow.addView(
                tab,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) {
                        marginStart = dp(TAB_MARGIN_DP)
                    }
                },
            )
            tab
        }
    }

    /** Syncs the folder name, nav button states and tab highlight with the current page. */
    private fun updateHeaderAndTabs() {
        val index = currentFolderIndex
        folderNameLabel.text = entries.getOrNull(index)?.title ?: ""
        val hasPrev = index > 0
        prevFolderButton.isEnabled = hasPrev
        prevFolderButton.alpha = if (hasPrev) 1f else DISABLED_BUTTON_ALPHA
        val hasNext = index >= 0 && index < entries.size - 1
        nextFolderButton.isEnabled = hasNext
        nextFolderButton.alpha = if (hasNext) 1f else DISABLED_BUTTON_ALPHA
        tabViews.forEachIndexed { tabIndex, tab ->
            val entry = entries.getOrNull(tabIndex) ?: return@forEachIndexed
            val active = tabIndex == index
            tab.background = pillBackground(
                if (active) entry.accentColor else withAlpha(Color.WHITE, TAB_INACTIVE_FILL_ALPHA),
            )
            tab.setTextColor(Color.WHITE)
            tab.alpha = if (active) 1f else INACTIVE_TAB_TEXT_ALPHA
        }
        val activeTab = tabViews.getOrNull(index) ?: return
        tabsScroll.post {
            val target = activeTab.left - (tabsScroll.width - activeTab.width) / 2
            tabsScroll.smoothScrollTo(maxOf(0, target), 0)
        }
    }

    private fun createAppPage(entry: FolderEntry): View {
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        entry.apps.chunked(APPS_PER_ROW).forEach { rowApps ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
            }
            for (column in 0 until APPS_PER_ROW) {
                val cell = if (column < rowApps.size) {
                    createAppCell(rowApps[column])
                } else {
                    View(context)
                }
                row.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            list.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        scroll.addView(
            list,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        return scroll
    }

    private fun createAppCell(item: ItemInfo): View {
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val paddingV = dp(CELL_PADDING_V_DP)
            setPadding(dp(CELL_PADDING_H_DP), paddingV, dp(CELL_PADDING_H_DP), paddingV)
            isClickable = true
            tag = item
            setOnClickListener(ItemClickHandler.INSTANCE)
        }
        val icon = ImageView(context).apply {
            (item as? ItemInfoWithIcon)?.let { setImageDrawable(it.newIcon(context)) }
        }
        cell.addView(
            icon,
            LayoutParams(dp(APP_ICON_SIZE_DP), dp(APP_ICON_SIZE_DP)),
        )
        val label = TextView(context).apply {
            text = item.title?.toString() ?: ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, APP_LABEL_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            maxLines = APP_LABEL_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
        }
        cell.addView(
            label,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(LABEL_MARGIN_TOP_DP)
            },
        )
        return cell
    }

    private fun configureNavButton(
        button: TextView,
        glyph: String,
        descriptionRes: Int,
        onClick: () -> Unit,
    ) {
        button.apply {
            text = glyph
            contentDescription = context.getString(descriptionRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_BUTTON_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            background = circleBackground()
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun pillBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(PILL_CORNER_RADIUS_DP).toFloat()
        }
    }

    private fun circleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(Color.WHITE, NAV_BUTTON_FILL_ALPHA))
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
        private const val HORIZONTAL_PADDING_DP = 20
        private const val TOP_PADDING_DP = 28
        private const val BOTTOM_PADDING_DP = 20

        private const val BACK_TEXT_SIZE_SP = 13f
        private const val BACK_FILL_ALPHA = 0x26
        private const val BACK_PADDING_H_DP = 12
        private const val BACK_PADDING_V_DP = 6
        private const val FOLDER_NAME_TEXT_SIZE_SP = 16f
        private const val NAME_MARGIN_START_DP = 12
        private const val NAME_MARGIN_END_DP = 8
        private const val NAV_BUTTON_SIZE_DP = 28
        private const val NAV_BUTTON_TEXT_SIZE_SP = 20f
        private const val NAV_BUTTON_FILL_ALPHA = 0x1A
        private const val NAV_BUTTON_MARGIN_DP = 6
        private const val DISABLED_BUTTON_ALPHA = 0.35f
        private const val PREV_GLYPH = "‹"
        private const val NEXT_GLYPH = "›"

        private const val PILL_CORNER_RADIUS_DP = 16
        private const val TABS_MARGIN_TOP_DP = 12
        private const val TAB_TEXT_SIZE_SP = 12f
        private const val TAB_PADDING_H_DP = 12
        private const val TAB_PADDING_V_DP = 6
        private const val TAB_MARGIN_DP = 6
        private const val TAB_INACTIVE_FILL_ALPHA = 0x1A
        private const val INACTIVE_TAB_TEXT_ALPHA = 0.7f

        private const val PAGER_MARGIN_TOP_DP = 12
        private const val APPS_PER_ROW = 4
        private const val APP_ICON_SIZE_DP = 48
        private const val APP_LABEL_TEXT_SIZE_SP = 11f
        private const val APP_LABEL_MAX_LINES = 2
        private const val CELL_PADDING_H_DP = 4
        private const val CELL_PADDING_V_DP = 10
        private const val LABEL_MARGIN_TOP_DP = 4
    }
}
