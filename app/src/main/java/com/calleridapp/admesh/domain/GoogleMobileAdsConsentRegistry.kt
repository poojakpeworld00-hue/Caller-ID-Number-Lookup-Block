package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm.OnConsentFormDismissedListener
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

class GoogleMobileAdsConsentRegistry private constructor(context: Context) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    fun interface OnConsentGatheringCompleteListener {
        fun consentGatheringComplete(error: FormError?)
    }

    fun canRequestAds(): Boolean {
        return consentInformation.canRequestAds()
    }

    val isPrivacyOptionsRequired: Boolean
        get() = (consentInformation.privacyOptionsRequirementStatus == PrivacyOptionsRequirementStatus.REQUIRED)

    fun gatherConsent(
        activity: Activity, onConsentGatheringCompleteListener: OnConsentGatheringCompleteListener
    ) {
        val debugSettings: ConsentDebugSettings =
            ConsentDebugSettings.Builder(activity)
                .build()

        val params: ConsentRequestParameters =
            ConsentRequestParameters.Builder().setConsentDebugSettings(debugSettings).build()

        consentInformation.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                { formError: FormError? ->
                    // Consent has been gathered.
                    onConsentGatheringCompleteListener.consentGatheringComplete(formError)
                })
        }, { requestConsentError: FormError? ->
            onConsentGatheringCompleteListener.consentGatheringComplete(
                requestConsentError
            )
        })
    }

    fun showPrivacyOptionsForm(
        activity: Activity, onConsentFormDismissedListener: OnConsentFormDismissedListener
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, onConsentFormDismissedListener)
    }

    companion object {
        private var instance: GoogleMobileAdsConsentRegistry? = null
        fun getInstance(context: Context): GoogleMobileAdsConsentRegistry {
            if (instance == null) {
                instance = GoogleMobileAdsConsentRegistry(context)
            }

            return instance!!
        }
    }
}