package com.calleridapp.admesh.presentation

import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.calleridapp.numberlookup.R
import java.util.Locale
import kotlin.math.floor

/**
 * Extras of the redesigned native ad layouts (Claude Design "Ads Redesign"): Remote Config
 * colours, the rating line (`ad_stars`) and the glowing green Install button of the mid
 * native (`tag="glow"`).
 *
 * Colours: the ad theme from Remote Config (`bgColor`, `textColor`, `btnColor`, `btnText`,
 * stored as NativeBgColor / NativetxtColor / NativebtnColor / NativebtntxtColor) wins; a key
 * that is missing or not a valid colour keeps the redesign's theme colour (light / dark).
 * Only the fill is recoloured, so the rounded card, border and button shapes stay.
 */
object NativeAdLook {

    fun bind(adView: NativeAdView, nativeAd: NativeAd) {
        applyCardSpacing(adView)
        applyRemoteColors(adView)
        bindStars(adView, nativeAd)
        adView.findViewById<TextView?>(R.id.ad_call_to_action)?.let { cta ->
            if (cta.tag == "glow") glow(cta)
        }
    }

    /**
     * The card's margins and shadow. Ads are inflated without a parent, so the root margins in the
     * layout file are dropped on the way into their frame; set here to the same values the layouts
     * give their shimmer placeholders. Kept on existing params when the view already has some.
     */
    private fun applyCardSpacing(adView: NativeAdView) {
        val res = adView.resources
        val h = res.getDimensionPixelSize(R.dimen.ad_card_margin_h)
        val top = res.getDimensionPixelSize(R.dimen.ad_card_margin_top)
        val bottom = res.getDimensionPixelSize(R.dimen.ad_card_margin_bottom)
        val params = (adView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        params.setMargins(h, top, h, bottom)
        adView.layoutParams = params
        // The rounded card background is the outline, so the shadow follows its corners.
        adView.elevation = res.getDimension(R.dimen.ad_card_elevation)
        // Screens host the ad in frames with different insets: some full width, some already inside
        // padded content. Aim for the card's *on-screen* distance from each edge to be [h], so a
        // frame that already sits 24dp in does not end up at 40dp and narrower than its screen.
        adView.post { fitSideMargins(adView, h) }
    }

    private fun fitSideMargins(adView: NativeAdView, target: Int) {
        val parent = adView.parent as? View ?: return
        val root = adView.rootView
        val windowWidth = root.width
        if (parent.width <= 0 || windowWidth <= 0) return
        // Layout position, not getLocationInWindow: that one includes translationX, and the
        // launcher's side panels are still sliding in when their ad binds - a frame caught 50dp
        // short of its place got a 0 left margin and a wide right one, a card off-centre for good.
        var x = 0
        var v: View? = parent
        while (v != null && v !== root) {
            x += v.left
            v = v.parent as? View
        }
        val insetLeft = x.coerceAtLeast(0)
        val insetRight = (windowWidth - x - parent.width).coerceAtLeast(0)
        val params = adView.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val left = (target - insetLeft).coerceAtLeast(0)
        val right = (target - insetRight).coerceAtLeast(0)
        if (params.leftMargin == left && params.rightMargin == right) return
        params.leftMargin = left
        params.rightMargin = right
        adView.layoutParams = params
    }

    private fun applyRemoteColors(adView: NativeAdView) {
        val pref = com.calleridapp.admesh.domain.AdsVault.getInstance(adView.context)
        fun rc(key: String): Int? = pref.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try { android.graphics.Color.parseColor(it) } catch (_: IllegalArgumentException) { null }
        }
        rc("NativeBgColor")?.let { fill(adView, it) }
        rc("NativetxtColor")?.let { txt ->
            adView.findViewById<TextView?>(R.id.ad_headline)?.setTextColor(txt)
            adView.findViewById<TextView?>(R.id.ad_body)?.setTextColor(txt)
        }
        adView.findViewById<TextView?>(R.id.ad_call_to_action)?.let { cta ->
            rc("NativebtnColor")?.let { fill(cta, it) }
            rc("NativebtntxtColor")?.let { btnTxt ->
                cta.setTextColor(btnTxt)
                cta.compoundDrawablesRelative.forEach { d -> d?.mutate()?.setTint(btnTxt) }
            }
        }
    }

    /** Recolour a shape background's fill (a gradient becomes solid) keeping its corners / stroke. */
    private fun fill(v: View, color: Int) {
        when (val bg = v.background?.mutate()) {
            is android.graphics.drawable.GradientDrawable -> bg.setColor(color)
            null -> v.setBackgroundColor(color)
            else -> v.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    /** "★★★★☆ 4.6" (mid) or "★ 4.6 · Free" (big) — hidden when the ad has no rating. */
    private fun bindStars(adView: NativeAdView, nativeAd: NativeAd) {
        val stars = adView.findViewById<TextView?>(R.id.ad_stars) ?: return
        val rating = nativeAd.starRating
        if (rating == null || rating <= 0.0) {
            stars.visibility = View.GONE
            return
        }
        val value = String.format(Locale.getDefault(), "%.1f", rating)
        stars.text = if (adView.findViewById<View?>(R.id.ad_media) == null) {
            // Five stars, the last ones faded as in the design
            val full = floor(rating + 0.5).toInt().coerceIn(0, 5)
            android.text.SpannableStringBuilder().apply {
                append("★".repeat(5))
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.ad_star)), 0, full, 0)
                if (full < 5) setSpan(
                    android.text.style.ForegroundColorSpan(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(color(stars, R.color.ad_star), 0x99)
                    ), full, 5, 0
                )
                append("  ")
                val start = length
                append(value)
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.lk_ink)), start, length, 0)
            }
        } else {
            android.text.SpannableStringBuilder().apply {
                append("★ ")
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.ad_star)), 0, 1, 0)
                append(listOfNotNull(value, nativeAd.price?.takeIf { it.isNotBlank() }).joinToString(" · "))
            }
        }
        stars.visibility = View.VISIBLE
        adView.starRatingView = stars
    }

    /** adGlow 2.2s: the green shadow breathes 4dp ↔ 9dp. */
    private fun glow(cta: TextView) {
        val dp = cta.resources.displayMetrics.density
        (cta.getTag(R.id.ad_call_to_action) as? ValueAnimator)?.cancel()
        val anim = ValueAnimator.ofFloat(4 * dp, 9 * dp).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { cta.elevation = it.animatedValue as Float }
        }
        cta.setTag(R.id.ad_call_to_action, anim)
        cta.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = anim.start()
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
        if (cta.isAttachedToWindow) anim.start()
    }

    private fun color(v: View, res: Int) = androidx.core.content.ContextCompat.getColor(v.context, res)
}
