package com.calleridapp.numberlookup.ui.language

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.ItemLanguageBinding

class LocaleAdapter(
    private val onClick: (LocaleItem) -> Unit
) : RecyclerView.Adapter<LocaleAdapter.VH>() {

    private val items = mutableListOf<LocaleItem>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LocaleItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Moves the selection. Only the two affected rows are notified, and with a
     * payload, so RecyclerView rebinds nothing else and the row can animate the
     * change instead of snapping to it.
     */
    fun setSelected(tag: String) {
        if (tag == selectedTag) return
        val previous = items.indexOfFirst { it.tag == selectedTag }
        val next = items.indexOfFirst { it.tag == tag }
        selectedTag = tag
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous, PAYLOAD_SELECTION)
        if (next != RecyclerView.NO_POSITION) notifyItemChanged(next, PAYLOAD_SELECTION)
    }

    fun setCurrent(tag: String) {
        currentTag = tag
    }

    inner class VH(val binding: ItemLanguageBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Kept so a recycled row can cancel a half-finished animation. */
        private var running: Animator? = null

        init {
            binding.root.setOnClickListener { view ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                tapFeedback(view)
                onClick(items[position])
            }
        }

        /** Quick press-in / release bounce so the tap registers immediately. */
        private fun tapFeedback(view: View) {
            view.animate().cancel()
            view.animate()
                .scaleX(TAP_SCALE).scaleY(TAP_SCALE)
                .setDuration(TAP_DOWN_MS)
                .withEndAction {
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(TAP_UP_MS)
                        .setInterpolator(OvershootInterpolator(TAP_OVERSHOOT))
                        .start()
                }
                .start()
        }

        fun cancelAnimations() {
            running?.cancel()
            running = null
            binding.radio.scaleX = 1f
            binding.radio.scaleY = 1f
            binding.root.background?.alpha = OPAQUE
        }

        fun bindSelection(selected: Boolean, animate: Boolean) {
            val ctx = binding.root.context
            val nativeColor = ContextCompat.getColor(
                ctx, if (selected) R.color.on_primary_container else R.color.on_surface
            )

            cancelAnimations()
            binding.root.isActivated = selected
            binding.radio.isActivated = selected

            if (!animate) {
                binding.tvNative.setTextColor(nativeColor)
                return
            }

            // Radio pops, the row tint fades in, and the leading label
            // cross-fades to the on-container colour.
            val pop = ObjectAnimator.ofPropertyValuesHolder(
                binding.radio,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, POP_FROM, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, POP_FROM, 1f)
            ).apply {
                duration = POP_MS
                interpolator = OvershootInterpolator(POP_OVERSHOOT)
            }

            val tint = ValueAnimator.ofInt(0, OPAQUE).apply {
                duration = TINT_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { binding.root.background?.alpha = it.animatedValue as Int }
            }

            val text = ValueAnimator.ofArgb(binding.tvNative.currentTextColor, nativeColor).apply {
                duration = TINT_MS
                addUpdateListener { binding.tvNative.setTextColor(it.animatedValue as Int) }
            }

            running = AnimatorSet().apply {
                // Only the row gaining selection fills in; the outgoing row just
                // clears, so two tints are never visible at once.
                if (selected) playTogether(pop, tint, text) else playTogether(pop, text)
                start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLanguageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            val ctx = root.context
            tvFlag.text = item.flag
            tvNative.text = item.nativeName
            tvName.text =
                if (item.tag == currentTag) ctx.getString(R.string.language_current)
                else item.name
        }
        holder.bindSelection(item.tag == selectedTag, animate = false)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(items[position].tag == selectedTag, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelAnimations()
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val OPAQUE = 255

        const val TAP_SCALE = 0.97f
        const val TAP_DOWN_MS = 70L
        const val TAP_UP_MS = 160L
        const val TAP_OVERSHOOT = 2.2f

        const val POP_FROM = 0.72f
        const val POP_MS = 260L
        const val POP_OVERSHOOT = 3.0f

        const val TINT_MS = 220L
    }
}
