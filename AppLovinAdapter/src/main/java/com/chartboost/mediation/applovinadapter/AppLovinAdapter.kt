/*
 * Copyright 2022-2023 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.applovinadapter

import android.content.Context
import android.provider.Settings
import android.util.Size
import android.view.View.GONE
import com.applovin.adview.*
import com.applovin.sdk.*
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation AppLovin SDK adapter.
 */
class AppLovinAdapter : PartnerAdapter {
    companion object {
        /**
         * Enable/disable AppLovin's test mode. Remember to set this to false in production.
         *
         * @param context The current [Context].
         * @param enabled True to enable test mode, false otherwise.
         */
        public fun setTestMode(context: Context, enabled: Boolean) {
            if (enabled) {
                CoroutineScope(IO).launch {
                    val adInfo = try {
                        AdvertisingIdClient.getAdvertisingIdInfo(context).id
                    } catch (e: Exception) {
                        context.contentResolver.let { resolver ->
                            Settings.Secure.getString(resolver, "advertising_id")
                        }
                    }

                    adInfo?.let { adId ->
                        withContext(Main) {
                            AppLovinSdk.getInstance(context).settings.testDeviceAdvertisingIds =
                                listOf(adId)
                        }
                    } ?: run {
                        PartnerLogController.log(
                            CUSTOM,
                            "AppLovin test mode is disabled. No advertising id found."
                        )
                        AppLovinSdk.getInstance(context).settings.testDeviceAdvertisingIds =
                            emptyList()
                    }
                }
            } else {
                AppLovinSdk.getInstance(context).settings.testDeviceAdvertisingIds = emptyList()
            }

            PartnerLogController.log(
                CUSTOM,
                "AppLovin test mode is ${
                    if (enabled) "enabled. Remember to disable it before publishing."
                    else "disabled."
                }"
            )
        }

        /**
         * Enable/disable AppLovin's mute setting.
         *
         * @param context The current [Context].
         * @param muted True to mute, false otherwise.
         */
        public fun setMuted(context: Context, muted: Boolean) {
            AppLovinSdk.getInstance(context).settings.isMuted = muted
            PartnerLogController.log(
                CUSTOM,
                "AppLovin video creatives will be ${if (muted) "muted" else "unmuted"}."
            )
        }

        /**
         * Enable/disable AppLovin's verbose logging.
         *
         * @param context The current [Context].
         * @param enabled True to enable verbose logging, false otherwise.
         */
        public fun setVerboseLogging(context: Context, enabled: Boolean) {
            AppLovinSdk.getInstance(context).settings.setVerboseLogging(enabled)
            PartnerLogController.log(
                CUSTOM,
                "AppLovin verbose logging is ${if (enabled) "enabled" else "disabled"}."
            )
        }
    }

    /**
     * The AppLovin SDK needs an instance that is later passed to its ad lifecycle methods.
     */
    private var appLovinSdk: AppLovinSdk? = null

    /**
     * The AppLovin SDK needs a context for its privacy methods.
     */
    private var appContext: Context? = null

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Get the AppLovin SDK version.
     */
    override val partnerSdkVersion: String
        get() = AppLovinSdk.VERSION

    /**
     * Get the AppLovin adapter version.
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
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_APPLOVIN_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "applovin"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "AppLovin"

    /**
     * Initialize the AppLovin SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize AppLovin.
     *
     * @return Result.success(Unit) if AppLovin successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue("sdk_key")
            ).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { sdkKey ->
                    context.applicationContext?.let {
                        // Save the application context for later usage.
                        appContext = it

                        appLovinSdk = AppLovinSdk.getInstance(
                            sdkKey,
                            AppLovinSdkSettings(it),
                            it
                        ).also { sdk ->
                            sdk.initializeSdk {
                                sdk.mediationProvider = "Chartboost"
                                sdk.setPluginVersion(adapterVersion)
                                continuation.resume(
                                    Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
                                )
                            }
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "No SDK key found.")
                continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)))
            }
        }
    }

    /**
     * Notify the AppLovin SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        // Setting GDPR applicability is a NO-OP because AppLovin does not have a corresponding API.

        val userConsented = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        AppLovinPrivacySettings.setHasUserConsent(userConsented, context)
    }

    /**
     * Notify AppLovin of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )


        AppLovinPrivacySettings.setDoNotSell(!hasGrantedCcpaConsent, context)
    }

    /**
     * Notify AppLovin of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        AppLovinPrivacySettings.setIsAgeRestrictedUser(isSubjectToCoppa, context)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
    }

    /**
     * Attempt to load an AppLovin ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitialAd(request, partnerAdListener)
            AdFormat.REWARDED -> loadRewardedAd(request, partnerAdListener)
            AdFormat.BANNER -> loadBannerAd(
                context,
                request,
                partnerAdListener
            )
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded AppLovin ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val partnerAdListener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            // Banner ads don't have their own show.
            AdFormat.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL -> showInterstitialAd(context, partnerAd, partnerAdListener)
            AdFormat.REWARDED -> showRewardedAd(context, partnerAd, partnerAdListener)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Discard unnecessary AppLovin ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)
        listeners.remove(partnerAd.request.identifier)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Attempt to load an AppLovin banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            if (appLovinSdk == null) {
                PartnerLogController.log(LOAD_FAILED)
                continuation.resume(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_NOT_INITIALIZED))
                )
                return@suspendCoroutine
            }

            AppLovinAdView(
                appLovinSdk,
                getAppLovinAdSize(request.size),
                request.partnerPlacement,
                context
            ).apply {
                // Apply the Ad Load Listener to the AppLovinAdView
                setAdLoadListener(object : AppLovinAdLoadListener {
                    override fun adReceived(ad: AppLovinAd) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = this@apply,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        )
                    }

                    override fun failedToReceiveAd(errorCode: Int) {
                        PartnerLogController.log(LOAD_FAILED, "$errorCode")
                        continuation.resume(
                            Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode)))
                        )
                    }
                })

                // Apply the AdView Event Listener to the AppLovinAdView
                setAdViewEventListener(object : AppLovinAdViewEventListener {
                    override fun adOpenedFullscreen(ad: AppLovinAd, adView: AppLovinAdView) {}
                    override fun adClosedFullscreen(ad: AppLovinAd, adView: AppLovinAdView) {}
                    override fun adLeftApplication(ad: AppLovinAd, adView: AppLovinAdView) {}
                    override fun adFailedToDisplay(
                        ad: AppLovinAd,
                        adView: AppLovinAdView,
                        errorCode: AppLovinAdViewDisplayErrorCode
                    ) {
                        PartnerLogController.log(SHOW_FAILED, "$errorCode")
                    }
                })

                // Apply the Ad Click Listener to the AppLovinAdView
                setAdClickListener { ad ->
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }

                // Load and immediately show the AppLovin banner ad.
                loadNextAd()
            }
        }
    }

    /**
     * Find the most appropriate AppLovin ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The AppLovin ad size that best matches the given [Size].
     */
    private fun getAppLovinAdSize(size: Size?): AppLovinAdSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> AppLovinAdSize.BANNER
                it in 90 until 250 -> AppLovinAdSize.LEADER
                it >= 250 -> AppLovinAdSize.MREC
                else -> AppLovinAdSize.BANNER
            }
        } ?: AppLovinAdSize.BANNER
    }

    /**
     * Attempt to load an AppLovin interstitial ad.
     *
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            appLovinSdk?.let {
                // Save listener for later usage.
                listeners[request.identifier] = partnerAdListener

                it.adService.loadNextAdForZoneId(
                    request.partnerPlacement,
                    object : AppLovinAdLoadListener {
                        override fun adReceived(ad: AppLovinAd?) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = ad,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun failedToReceiveAd(errorCode: Int) {
                            PartnerLogController.log(LOAD_FAILED, "$errorCode")
                            continuation.resume(
                                Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode)))
                            )
                        }
                    }
                )
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "AppLovin SDK instance is null.")
                continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_INSTANCE_NOT_FOUND)))
            }
        }
    }

    /**
     * Attempt to load an AppLovin rewarded ad.
     *
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            appLovinSdk?.let {

                // Save listener for later usage.
                listeners[request.identifier] = partnerAdListener

                val rewardedAd =
                    AppLovinIncentivizedInterstitial.create(request.partnerPlacement, it)

                rewardedAd.preload(object : AppLovinAdLoadListener {
                    override fun adReceived(ad: AppLovinAd?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = ad,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        )
                    }

                    override fun failedToReceiveAd(errorCode: Int) {
                        PartnerLogController.log(LOAD_FAILED, "$errorCode")
                        continuation.resume(
                            Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode)))
                        )
                    }
                })
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "AppLovin SDK instance is null.")
                continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_INSTANCE_NOT_FOUND)))
            }
        }
    }

    /**
     * Attempt to show an AppLovin interstitial ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        context: Context,
        partnerAd: PartnerAd,
        partnerAdListener: PartnerAdListener?
    ): Result<PartnerAd> {
        return (partnerAd.ad as? AppLovinAd)?.let {
            suspendCoroutine { continuation ->
                val interstitialAd =
                    AppLovinInterstitialAd.create(appLovinSdk, context)

                interstitialAd.setAdDisplayListener(object :
                    AppLovinAdDisplayListener {
                    override fun adDisplayed(ad: AppLovinAd?) {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun adHidden(ad: AppLovinAd?) {
                        PartnerLogController.log(DID_DISMISS)
                        partnerAdListener?.onPartnerAdDismissed(partnerAd, null)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdDismissed for AppLovin adapter."
                            )
                    }
                })

                interstitialAd.setAdClickListener {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
                        CUSTOM,
                        "Unable to fire onPartnerAdClicked for AppLovin adapter."
                    )
                }

                interstitialAd.showAndRender(it)
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to show an AppLovin rewarded ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        context: Context,
        partnerAd: PartnerAd,
        partnerAdListener: PartnerAdListener?
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val rewardedAd = AppLovinIncentivizedInterstitial.create(appLovinSdk)

            var isUserVerified: Boolean? = null

            val rewardListener: AppLovinAdRewardListener = object : AppLovinAdRewardListener {
                override fun userRewardVerified(
                    appLovinAd: AppLovinAd,
                    map: Map<String, String>
                ) {
                    // user should be granted the reward upon completion
                    isUserVerified = true
                }

                override fun userOverQuota(appLovinAd: AppLovinAd, map: Map<String, String>?) {}
                override fun userRewardRejected(
                    appLovinAd: AppLovinAd,
                    map: Map<String, String>?
                ) {
                    // user has been denylisted and should not be granted a reward
                    isUserVerified = false
                }

                override fun validationRequestFailed(
                    appLovinAd: AppLovinAd,
                    responseCode: Int
                ) {
                    // user could not be verified
                    PartnerLogController.log(
                        CUSTOM,
                        "validationRequestFailed for $partnerAd. Error: $responseCode"
                    )
                }
            }

            val playbackListener: AppLovinAdVideoPlaybackListener =
                object : AppLovinAdVideoPlaybackListener {
                    override fun videoPlaybackBegan(appLovinAd: AppLovinAd) {
                    }

                    override fun videoPlaybackEnded(
                        appLovinAd: AppLovinAd,
                        percentViewed: Double,
                        fullyWatched: Boolean
                    ) {
                        // TODO: HB-4119: Need to check if we need to add logic here for rewards.
                        // Only stop the reward if the user was explicity deny listed by AppLovin
                        if (isUserVerified == false) {
                            PartnerLogController.log(
                                CUSTOM,
                                "Unable to reward due to user being denylisted by AppLovin."
                            )
                            return
                        }

                        PartnerLogController.log(DID_REWARD)
                        partnerAdListener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for AppLovin adapter."
                            )

                    }
                }

            val displayListener: AppLovinAdDisplayListener = object : AppLovinAdDisplayListener {
                override fun adDisplayed(appLovinAd: AppLovinAd) {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    continuation.resume(Result.success(partnerAd))
                    // TODO: HB-4119: We may need to check if the impression is recorded here.
                }

                override fun adHidden(appLovinAd: AppLovinAd) {
                    PartnerLogController.log(DID_DISMISS)
                    partnerAdListener?.onPartnerAdDismissed(partnerAd, null)
                        ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdDismissed for AppLovin adapter."
                        )
                }
            }

            val clickListener =
                AppLovinAdClickListener {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
                        CUSTOM,
                        "Unable to fire onPartnerAdClicked for AppLovin adapter."
                    )
                }

            rewardedAd.show(
                partnerAd.ad as AppLovinAd,
                context,
                rewardListener,
                playbackListener,
                displayListener,
                clickListener
            )
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Destroy the current AppLovin banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is AppLovinAdView) {
                it.visibility = GONE
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an AppLovinAdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Convert a given AppLovin error code into a [ChartboostMediationError].
     *
     * @param error The AppLovin error code as an [Int].
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: Int) = when (error) {
        AppLovinErrorCodes.NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
        AppLovinErrorCodes.NO_NETWORK -> ChartboostMediationError.CM_NO_CONNECTIVITY
        AppLovinErrorCodes.SDK_DISABLED -> ChartboostMediationError.CM_INITIALIZATION_SKIPPED
        // AppLovin is currently not part of programmatic bidding with Chartboost Mediation. Only waterfall.
        AppLovinErrorCodes.INVALID_AD_TOKEN -> ChartboostMediationError.CM_LOAD_FAILURE_AUCTION_NO_BID
        AppLovinErrorCodes.UNABLE_TO_RENDER_AD -> ChartboostMediationError.CM_SHOW_FAILURE_UNKNOWN
        AppLovinErrorCodes.FETCH_AD_TIMEOUT -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
        AppLovinErrorCodes.UNABLE_TO_PRECACHE_RESOURCES, AppLovinErrorCodes.UNABLE_TO_PRECACHE_VIDEO_RESOURCES, AppLovinErrorCodes.UNABLE_TO_PRECACHE_IMAGE_RESOURCES -> ChartboostMediationError.CM_LOAD_FAILURE_OUT_OF_STORAGE
        AppLovinErrorCodes.INCENTIVIZED_NO_AD_PRELOADED -> ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY
        AppLovinErrorCodes.INVALID_RESPONSE -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BID_RESPONSE
        AppLovinErrorCodes.INVALID_ZONE -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT
        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}
