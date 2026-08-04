package com.calleridapp.numberlookup.launcher.helpers

import android.view.View
import com.calleridapp.numberlookup.R

/**
 * Fills in the shared 4-segment progress row (onboarding_progress_bar.xml) up to [activeStep]
 * (1-indexed): Welcome, Set default launcher, Intro, Language.
 */
fun setOnboardingStep(steps: List<View>, activeStep: Int) {
    steps.forEachIndexed { index, view ->
        view.setBackgroundResource(
            if (index < activeStep) R.drawable.onboarding_progress_active
            else R.drawable.onboarding_progress_inactive
        )
    }
}
