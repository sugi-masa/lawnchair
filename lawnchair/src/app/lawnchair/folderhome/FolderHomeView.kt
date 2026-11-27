package app.lawnchair.folderhome

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.WorkspaceData
import com.android.launcher3.touch.ItemClickHandler
import com.android.launcher3.util.Executors
import java.util.function.Predicate

/**
 * Full-screen layer for the Folder Home state: the space hub, and the only home
 * surface of the launcher.
 *
 * The upper zone reserves space for widgets (the widget host is wired up in a
 * later unit). The lower zone shows one card per folder in the model, six cards
 * (2 columns x 3 rows) per page, paging horizontally inside the zone only.
 *
 * There is no gesture out of the hub into the workspace: swiping up opens All
 * Apps through the launcher's own All Apps swipe controller, and everything
 * else returns here. Card taps open the stock folder popup for now; a later
 * unit replaces that with paged folder pages.
 */
class FolderHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val folderPager = FolderHubPager(context)
    private val emptyLabel = TextView(context)

    private var folderInfosById: Map<Int, FolderInfo> = emptyMap()
    private var refreshQueued = false

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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(HORIZONTAL_PADDING_DP),
                dp(TOP_PADDING_DP),
                dp(HORIZONTAL_PADDING_DP),
                dp(BOTTOM_PADDING_DP),
            )
        }

        // Widget zone: the layout space is reserved now; the contents come later.
        val widgetZone = FrameLayout(context).apply {
            background = zoneBackground()
            val label = TextView(context).apply {
                text = context.getString(R.string.folder_home_widget_zone)
                setTextColor(Color.WHITE)
                alpha = SECONDARY_TEXT_ALPHA
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ZONE_LABEL_TEXT_SIZE_SP)
                gravity = Gravity.CENTER
            }
            addView(
                label,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                },
            )
        }
        root.addView(
            widgetZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                WIDGET_ZONE_WEIGHT,
            ),
        )

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
        root.addView(
            folderZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                FOLDER_ZONE_WEIGHT,
            ).apply {
                topMargin = dp(ZONE_GAP_DP)
            },
        )

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherAppState.getInstance(context).model.addCallbacks(modelCallbacks)
        requestRefresh()
    }

    override fun onDetachedFromWindow() {
        LauncherAppState.getInstance(context).model.removeCallbacks(modelCallbacks)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consume everything not handled by children so touches never leak to the
        // workspace below.
        return true
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
                    accentColor = ACCENT_COLORS[Math.floorMod(info.id, ACCENT_COLORS.size)],
                )
            }
        }
    }

    private fun applyFolderEntries(entries: List<FolderEntry>) {
        folderInfosById = entries.associate { it.info.id to it.info }
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val pages = entries.chunked(FOLDERS_PER_PAGE).map { pageEntries ->
            createFolderPage(pageEntries)
        }
        folderPager.setPages(pages, folderPager.currentPage)
    }

    private fun createFolderPage(entries: List<FolderEntry>): View {
        val grid = GridLayout(context).apply {
            columnCount = GRID_COLUMNS
            rowCount = GRID_ROWS
        }
        entries.forEachIndexed { index, entry ->
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / GRID_COLUMNS, GridLayout.FILL, 1f),
                GridLayout.spec(index % GRID_COLUMNS, GridLayout.FILL, 1f),
            ).apply {
                width = 0
                height = 0
                val margin = dp(CARD_MARGIN_DP)
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(createFolderCard(entry), params)
        }
        return grid
    }

    private fun createFolderCard(entry: FolderEntry): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBackground(entry.accentColor)
            val padding = dp(CARD_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            isClickable = true
            setOnClickListener { openFolder(entry.info.id) }
        }
        val preview = GridLayout(context).apply {
            columnCount = PREVIEW_COLUMNS
            rowCount = PREVIEW_ROWS
        }
        entry.previewItems.forEach { item ->
            val iconView = ImageView(context).apply {
                setImageDrawable(item.newIcon(context))
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(PREVIEW_ICON_SIZE_DP)
                height = dp(PREVIEW_ICON_SIZE_DP)
                val margin = dp(PREVIEW_ICON_MARGIN_DP)
                setMargins(margin, margin, margin, margin)
            }
            preview.addView(iconView, params)
        }
        card.addView(preview)

        val name = TextView(context).apply {
            text = entry.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FOLDER_NAME_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(
            name,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(CARD_TEXT_MARGIN_DP)
            },
        )

        val count = TextView(context).apply {
            text = resources.getQuantityString(
                R.plurals.folder_home_app_count,
                entry.appCount,
                entry.appCount,
            )
            setTextColor(Color.WHITE)
            alpha = SECONDARY_TEXT_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, APP_COUNT_TEXT_SIZE_SP)
            gravity = Gravity.CENTER
        }
        card.addView(
            count,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    /**
     * Opens a folder from its hub card.
     *
     * Provisional: replays the standard folder icon click so the stock folder
     * popup is shown. A later unit replaces this with paged folder pages, which
     * is why the lookup goes through the folder id only.
     */
    private fun openFolder(folderId: Int) {
        val folderInfo = folderInfosById[folderId] ?: return
        val launcher = Launcher.getLauncher(context)
        val iconView = launcher.workspace.mapOverItems { info, view ->
            view is FolderIcon && info?.id == folderInfo.id
        } ?: return
        ItemClickHandler.INSTANCE.onClick(iconView)
    }

    private fun cardBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(withAlpha(accentColor, CARD_FILL_ALPHA))
            setStroke(dp(1), withAlpha(accentColor, CARD_STROKE_ALPHA))
            cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
        }
    }

    private fun zoneBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), withAlpha(Color.WHITE, ZONE_STROKE_ALPHA))
            cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
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

    private data class FolderEntry(
        val info: FolderInfo,
        val title: String,
        val appCount: Int,
        val previewItems: List<ItemInfoWithIcon>,
        val accentColor: Int,
    )

    companion object {
        private const val BACKGROUND_COLOR = 0xF220202A.toInt()

        private const val HORIZONTAL_PADDING_DP = 20
        private const val TOP_PADDING_DP = 28
        private const val BOTTOM_PADDING_DP = 20
        private const val ZONE_GAP_DP = 16
        private const val WIDGET_ZONE_WEIGHT = 1f
        private const val FOLDER_ZONE_WEIGHT = 1.3f

        private const val GRID_COLUMNS = 2
        private const val GRID_ROWS = 3
        private const val FOLDERS_PER_PAGE = GRID_COLUMNS * GRID_ROWS
        private const val CARD_MARGIN_DP = 6
        private const val CARD_PADDING_DP = 12
        private const val CARD_TEXT_MARGIN_DP = 8
        private const val CORNER_RADIUS_DP = 18
        private const val CARD_FILL_ALPHA = 0x30
        private const val CARD_STROKE_ALPHA = 0x8C
        private const val ZONE_STROKE_ALPHA = 0x2E

        private const val PREVIEW_COLUMNS = 2
        private const val PREVIEW_ROWS = 2
        private const val PREVIEW_ITEM_COUNT = PREVIEW_COLUMNS * PREVIEW_ROWS
        private const val PREVIEW_ICON_SIZE_DP = 26
        private const val PREVIEW_ICON_MARGIN_DP = 2

        private const val ZONE_LABEL_TEXT_SIZE_SP = 13f
        private const val EMPTY_TEXT_SIZE_SP = 15f
        private const val FOLDER_NAME_TEXT_SIZE_SP = 14f
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
