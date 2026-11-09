package ua.olex.sonyanc

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews

/**
 * Pure layout decisions for both widgets, in dp. Kept free of Android types so every launcher
 * size can be unit-tested. Text sizes come in measured, so font scale is respected.
 */
internal object WidgetLayout {
    const val PAD = 8f // R.dimen.w_pad
    const val GAP = 4f // R.dimen.w_gap
    private const val ICON = 24f
    private const val TILE_PAD = 8f // tile horizontal padding, each side
    // Taller buttons turn pills into circles and D shapes; the group is centered instead.
    private const val MAX_ROW_STACKED = 80f
    private const val MAX_ROW_FLAT = 64f
    private const val MAX_COLUMN_TILE = 64f
    private const val MAX_RING = 84f

    enum class ModeVariant { ROW, INLINE, COLUMN }

    /** [group] is the height of the button group; the background always fills the widget. */
    data class ModeSpec(val variant: ModeVariant, val labels: Boolean, val icon: Float, val group: Float)

    /**
     * @param label width of the widest label ("Ambient") in dp.
     * @param line height of one label line in dp.
     */
    fun modes(width: Float, height: Float, label: Float, line: Float): ModeSpec {
        val w = width - 2 * PAD
        val h = height - 2 * PAD
        val columnHeight = 3 * 48f + 2 * GAP
        // Taller than wide (2×2, 2×3): stack the buttons, each a full-width pill.
        if (h >= columnHeight && h > w * 1.15f) {
            val tile = ((h - 2 * GAP) / 3).coerceAtMost(MAX_COLUMN_TILE)
            val labels = w >= 2 * TILE_PAD + ICON + 8 + label
            return ModeSpec(ModeVariant.COLUMN, labels, ICON, 3 * tile + 2 * GAP)
        }
        val tile = (w - 2 * GAP) / 3
        val inline = tile >= 2 * TILE_PAD + ICON + 8 + label
        val stackedHeight = h.coerceAtMost(MAX_ROW_STACKED)
        val stacked = tile >= 2 * TILE_PAD + label && stackedHeight >= ICON + 4 + line + 16
        val flat = h.coerceAtMost(MAX_ROW_FLAT)
        return when {
            // Wide enough for "icon label" side by side: the calmest layout, one line high.
            inline && (h < ICON + 4 + line + 16 || tile >= 2 * TILE_PAD + ICON + 8 + label + 16) ->
                ModeSpec(ModeVariant.INLINE, true, ICON, flat)
            stacked -> ModeSpec(ModeVariant.ROW, true, ICON, stackedHeight)
            inline -> ModeSpec(ModeVariant.INLINE, true, ICON, flat)
            else -> ModeSpec(ModeVariant.ROW, false, ICON, flat)
        }
    }

    /** Large slots get the dashboard; [estimate] and [scenes] rows appear only when they fit. */
    data class DashboardSpec(val estimate: Boolean, val scenes: Boolean, val labels: Boolean)

    /**
     * The lock-screen hub slot on a Pixel 10 Pro XL is about 416 × 275 dp; a home 4×2 about 340 × 208.
     * Returns null when the slot is too small for status, batteries and modes together.
     * @param title, value, small: measured line heights in dp (16, 15 and 12 sp).
     */
    fun dashboard(width: Float, height: Float, title: Float, value: Float, small: Float, label: Float = 54f): DashboardSpec? {
        val w = width - 2 * 16f
        val h = height - 2 * 14f
        val batteries = maxOf(44f, value + 2 + small)
        val base = title + 12 + batteries + 12 + 56
        if (w < 280f || h < base) return null
        val estimate = h >= base + 8 + small
        val scenes = h >= base + (if (estimate) 8 + small else 0f) + 8 + 40
        val tile = (w - 2 * GAP) / 3
        return DashboardSpec(estimate, scenes, labels = tile >= 2 * TILE_PAD + ICON + 8 + label)
    }

    /** [ring] is the outer ring diameter. Compact shows one ring (the emptier earbud) and the case as text. */
    data class BatterySpec(val compact: Boolean, val ring: Float, val labels: Boolean, val status: Boolean)

    /**
     * @param value height of the percentage line in dp.
     * @param small height of a label/status line in dp.
     */
    fun battery(width: Float, height: Float, value: Float, small: Float): BatterySpec {
        val w = width - 2 * PAD
        val h = height - 2 * PAD
        val compact = w < 120f
        val cell = if (compact) w else w / 3
        fun ring(labels: Boolean, status: Boolean) = minOf(
            cell - 8f,
            h - (6 + value) - (if (labels) 2 + small else 0f) - (if (status) 8 + small else 0f),
            MAX_RING,
        )
        val status = ring(!compact, true) >= if (compact) 36f else 52f
        val labels = !compact && ring(true, status) >= 40f
        return BatterySpec(compact, ring(labels, status).coerceAtLeast(20f), labels, status)
    }
}

/** Android side shared by [ModeWidget] and [BatteryWidget]. */
object Widgets {
    fun refreshAll(ctx: Context) {
        ModeWidget.refreshAll(ctx)
        BatteryWidget.refreshAll(ctx)
    }

    /** Widget text uses Pixel's system Google Sans when present, like the app. */
    private fun paint(ctx: Context, sp: Float, family: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)
        typeface = Typeface.create(family, Typeface.NORMAL)
    }

    /** Width of [text] in dp at [sp], after the user's font scale. */
    internal fun textWidth(ctx: Context, text: String, sp: Float, family: String = "google-sans-text-medium") =
        paint(ctx, sp, family).measureText(text) / ctx.resources.displayMetrics.density

    /** Height of one line in dp at [sp] without font padding. */
    internal fun lineHeight(ctx: Context, sp: Float, family: String = "google-sans-text") =
        paint(ctx, sp, family).fontMetrics.let { it.descent - it.ascent } / ctx.resources.displayMetrics.density

    /**
     * Every size the launcher may show this widget at (portrait and landscape, or each
     * size class). Each gets its own exact layout, so nothing is guessed from breakpoints.
     */
    internal fun sizes(options: Bundle?, fallback: List<SizeF>): List<SizeF> {
        if (options == null) return fallback
        val exact = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        if (!exact.isNullOrEmpty()) return exact.filter { it.width > 0 && it.height > 0 }.distinct().take(16)
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (minW <= 0 || minH <= 0) return fallback
        // Portrait uses min width × max height; landscape the reverse.
        return listOf(SizeF(minW.toFloat(), maxH.toFloat()), SizeF(maxW.toFloat(), minH.toFloat())).distinct()
    }

    /** One RemoteViews that carries an exact layout for each size (Android 12+). */
    internal fun forSizes(sizes: List<SizeF>, build: (SizeF) -> RemoteViews): RemoteViews =
        if (sizes.size > 1) RemoteViews(sizes.associateWith(build))
        else build(sizes.first())

    /**
     * Android 15+ launchers show a generated preview in the widget picker. Publish ours once per
     * app version; the call is rate-limited, so failures are ignored and retried next version.
     */
    fun publishPreviews(ctx: Context) {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        if (Prefs.previewVersion(ctx) == version) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val home = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        val ok = runCatching {
            mgr.setWidgetPreview(ComponentName(ctx, ModeWidget::class.java), home,
                ModeWidget.viewsFor(ctx, 340f, 96f, SonyProtocol.Mode.ANC, interactive = false, linked = true)) &&
                // The lock-screen hub shows the dashboard size of the same widget.
                mgr.setWidgetPreview(ComponentName(ctx, ModeWidget::class.java), AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
                    ModeWidget.viewsFor(ctx, 416f, 275f, SonyProtocol.Mode.ANC, interactive = false, linked = true,
                        battery = BatteryWidget.SAMPLE to System.currentTimeMillis())) &&
                mgr.setWidgetPreview(ComponentName(ctx, BatteryWidget::class.java), home,
                    BatteryWidget.viewsFor(ctx, 250f, 150f, BatteryWidget.SAMPLE, System.currentTimeMillis(), linked = true, lastSeen = 0L, interactive = false))
        }.getOrDefault(false)
        if (ok) Prefs.setPreviewVersion(ctx, version)
    }
}
