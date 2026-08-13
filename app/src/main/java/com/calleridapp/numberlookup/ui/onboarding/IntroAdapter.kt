package com.calleridapp.numberlookup.ui.onboarding

import android.animation.Animator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.databinding.ItemOnboardingBinding

class IntroAdapter(
    private val pages: List<IntroPage>
) : RecyclerView.Adapter<IntroAdapter.VH>() {

    private companion object {
        /** Below this the illustration is more noise than help, so it stops shrinking. */
        const val MIN_ART_SCALE = 0.45f
    }

    inner class VH(val binding: ItemOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Looping animators for the current page's illustration; cancelled on recycle. */
        private val anims = mutableListOf<Animator>()

        fun bind(page: IntroPage) {
            cancelAnims()
            val container = binding.artContainer
            container.removeAllViews()

            if (page.customArtRes != 0) {
                LayoutInflater.from(container.context).inflate(page.customArtRes, container, true)
                // Attach the exact per-element loop animations (float, pop, slide,
                // pulse, shield, stamp, sweep, blip) by view id.
                anims += IntroAnimations.attach(container)
            } else {
                val image = ImageView(container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(page.artRes)
                }
                container.addView(image)
            }

            binding.tvTitle.setText(page.titleRes)
            binding.tvDesc.setText(page.descRes)
            fitArt(container)
        }

        /**
         * The illustrations are drawn at a fixed size around the centre of a 210sdp box. The
         * box itself is elastic — a native ad at the bottom of the screen can leave far less
         * than that — so scale the artwork by however much of the design height survived.
         * Without this the fixed-size pieces just overflow the smaller box and collide with
         * the headline (the container deliberately does not clip them).
         */
        private fun fitArt(container: FrameLayout) {
            val art = container.getChildAt(0) ?: return
            container.doOnLayout {
                val designHeight = it.resources.getDimension(com.intuit.sdp.R.dimen._210sdp)
                if (designHeight <= 0f) return@doOnLayout
                val scale = (it.height / designHeight).coerceIn(MIN_ART_SCALE, 1f)
                art.scaleX = scale
                art.scaleY = scale
            }
        }

        private fun cancelAnims() {
            anims.forEach { it.cancel() }
            anims.clear()
        }

        fun recycle() = cancelAnims()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOnboardingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
