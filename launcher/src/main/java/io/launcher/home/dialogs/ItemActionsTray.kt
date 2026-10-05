package io.launcher.home.dialogs

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.launcher.home.R
import io.launcher.home.databinding.LnchItemActionsTrayBinding
import kotlin.math.max
import kotlin.math.min

/**
 * The long-press card on a home icon, a drawer tile or a widget, laid out as One UI does it: the
 * app's own shortcuts as rows on top, the launcher's actions as a row of labelled icons under them.
 *
 * It sits above the item it belongs to, or below it when there is no room above, so the item stays
 * in view - the framework PopupMenu it replaces was anchored to a point and usually covered it.
 * Focusable like that PopupMenu: a tap outside or Back closes it, and a drag that started with the
 * long press still reaches the launcher, which closes this when the finger moves.
 */
class ItemActionsTray(
    private val activity: Activity,
    private val shortcuts: List<Shortcut>,
    private val actions: List<Action>,
    private val onDismiss: () -> Unit,
) {
    class Shortcut(val icon: Drawable?, val label: CharSequence, val onClick: () -> Unit)

    class Action(@DrawableRes val icon: Int, @StringRes val label: Int, val onClick: () -> Unit)

    private val binding = LnchItemActionsTrayBinding.inflate(activity.layoutInflater)
    private val textColor = ContextCompat.getColor(activity, R.color.lnch_tray_text)
    private val secondaryColor = ContextCompat.getColor(activity, R.color.lnch_tray_text_secondary)

    private val window = PopupWindow(
        binding.root,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true,
    ).apply {
        // A transparent background is what lets a focusable PopupWindow close on an outside tap;
        // the card draws its own rounded one.
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = dp(12).toFloat()
        setOnDismissListener { onDismiss() }
    }

    val isShowing: Boolean get() = window.isShowing

    /** No shortcuts and no actions: [show] draws nothing. */
    val isEmpty: Boolean get() = shortcuts.isEmpty() && actions.isEmpty()

    /**
     * Shows the card for an item spanning [itemTop]..[itemBottom] on screen, centred on [centerX]
     * and kept [R.dimen.lnch_tray_screen_margin] inside the screen.
     */
    fun show(anchor: View, centerX: Float, itemTop: Float, itemBottom: Float) {
        // Nothing to offer (our own icon in the drawer: no App info, Hide or Uninstall, and no
        // shortcuts): drawing the card anyway left an empty white bar over the icons. The long
        // press still picks the icon up for a drag; the caller drops this tray on finger-up.
        if (isEmpty) return
        build()
        val res = activity.resources
        val screen = res.displayMetrics
        val margin = res.getDimensionPixelSize(R.dimen.lnch_tray_screen_margin)
        val gap = res.getDimensionPixelSize(R.dimen.lnch_tray_gap)

        val width = min(
            max(
                actions.size * res.getDimensionPixelSize(R.dimen.lnch_tray_action_width) + dp(12),
                res.getDimensionPixelSize(R.dimen.lnch_tray_min_width),
            ),
            min(res.getDimensionPixelSize(R.dimen.lnch_tray_max_width), screen.widthPixels - 2 * margin),
        )
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val height = binding.root.measuredHeight
        window.width = width

        val x = (centerX - width / 2f).toInt().coerceIn(margin, max(margin, screen.widthPixels - width - margin))
        val above = (itemTop - gap - height).toInt()
        val y = if (above >= statusBarHeight() + margin) above else (itemBottom + gap).toInt()

        binding.root.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            pivotX = (centerX - x).coerceIn(0f, width.toFloat())
            pivotY = if (y == above) height.toFloat() else 0f
        }
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        // Started once the popup's window is attached: an animator started on the view before
        // that never ran, and the card stayed at alpha 0 - a window that took touches but showed nothing.
        binding.root.post {
            binding.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                .withEndAction { binding.root.alpha = 1f; binding.root.scaleX = 1f; binding.root.scaleY = 1f }
                .start()
        }
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }

    private fun build() {
        binding.trayShortcutsUi.removeAllViews()
        shortcuts.forEach { binding.trayShortcutsUi.addView(shortcutRow(it)) }
        val hasShortcuts = shortcuts.isNotEmpty()
        binding.trayShortcutsUi.visibility = if (hasShortcuts) View.VISIBLE else View.GONE
        binding.trayDividerUi.visibility = if (hasShortcuts && actions.isNotEmpty()) View.VISIBLE else View.GONE

        binding.trayActionsUi.removeAllViews()
        actions.forEach {
            binding.trayActionsUi.addView(actionButton(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        binding.trayActionsUi.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shortcutRow(shortcut: Shortcut): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = ContextCompat.getDrawable(activity, R.drawable.lnch_tray_item_press)
        addView(ImageView(activity).apply {
            setImageDrawable(shortcut.icon)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(activity).apply {
            text = shortcut.label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        setOnClickListener { dismiss(); shortcut.onClick() }
    }

    private fun actionButton(action: Action): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(10), dp(2), dp(8))
        background = ContextCompat.getDrawable(activity, R.drawable.lnch_tray_item_press)
        contentDescription = activity.getString(action.label)
        addView(ImageView(activity).apply {
            setImageResource(action.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(textColor)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(activity).apply {
            setText(action.label)
            setTextColor(secondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        setOnClickListener { dismiss(); action.onClick() }
    }

    private fun statusBarHeight(): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
