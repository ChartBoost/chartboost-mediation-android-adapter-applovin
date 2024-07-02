package com.chartboost.mediation.applovinadapter

import android.content.Context
import android.provider.Settings
import com.applovin.sdk.AppLovinSdk
import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppLovinAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "applovin"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "AppLovin"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion: String = AppLovinSdk.VERSION

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_APPLOVIN_ADAPTER_VERSION

    /**
     * Whether AppLovin is in test mode.
     */
    var testMode = false
        private set

    /**
     * Enable/disable AppLovin's test mode. Remember to set this to false in production.
     *
     * @param context The current [Context].
     * @param enabled True to enable test mode, false otherwise.
     */
    fun setTestMode(
        context: Context,
        enabled: Boolean,
    ) {
        CoroutineScope(Main).launch {
            val adIds =
                withContext(Dispatchers.IO) {
                    try {
                        AdvertisingIdClient.getAdvertisingIdInfo(context).id
                    } catch (e: Exception) {
                        context.contentResolver.let { resolver ->
                            Settings.Secure.getString(resolver, "advertising_id")
                        }
                    }?.takeIf { enabled }?.let { listOf(it) } ?: emptyList()
                }

            testMode = enabled
            updateSdkSetting("test mode", enabled) {
                settings.testDeviceAdvertisingIds = adIds
            }
        }
    }

    /**
     * Enable/disable AppLovin's mute setting. True to mute video creatives, false otherwise.
     */
    var isMuted = false
        get() = AppLovinAdapter.appLovinSdk?.settings?.isMuted ?: field
        set(value) {
            updateSdkSetting("mute setting", value) {
                settings.isMuted = value
                field = value
            }
        }

    /**
     * Enable/disable AppLovin's verbose logging. True to enable verbose logging, false otherwise.
     */
    var isVerboseLoggingEnabled = false
        get() = AppLovinAdapter.appLovinSdk?.settings?.isVerboseLoggingEnabled ?: field
        set(value) {
            updateSdkSetting("verbose logging", value) {
                settings.setVerboseLogging(value)
                field = value
            }
        }

    /**
     * Enable/disable AppLovin's location sharing.
     */
    var isLocationCollectionEnabled = false
        get() = AppLovinAdapter.appLovinSdk?.settings?.isLocationCollectionEnabled ?: field
        set(value) {
            updateSdkSetting("location sharing", value) {
                settings.isLocationCollectionEnabled = value
                field = value
            }
        }

    /**
     * Generic function to update AppLovin SDK settings and log the action.
     *
     * @param settingName The name of the setting being modified.
     * @param enabled Whether the setting is enabled or not.
     * @param action The action to perform on the AppLovin SDK.
     */
    private fun updateSdkSetting(
        settingName: String,
        enabled: Boolean,
        action: AppLovinSdk.() -> Unit,
    ) {
        AppLovinAdapter.appLovinSdk?.let { sdk ->
            sdk.action()

            val status = if (enabled) "enabled" else "disabled"
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "AppLovin $settingName is $status.",
            )
        } ?: PartnerLogController.log(
            PartnerLogController.PartnerAdapterEvents.CUSTOM,
            "Unable to set $settingName. AppLovin SDK instance is null.",
        )
    }
}
