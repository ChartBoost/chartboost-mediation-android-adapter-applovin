/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.applovinadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import android.view.View.GONE
import com.applovin.adview.*
import com.applovin.sdk.*
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.Deprecated
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation AppLovin SDK adapter.
 */
class AppLovinAdapter : PartnerAdapter {
    companion object {
        /**
         * The AppLovin SDK instance. Note: instances are SDK Key specific. This is set in [setUp].
         * Do NOT create other instances from your app.
         */
        internal var appLovinSdk: AppLovinSdk? = null
    }

    /**
     * The AppLovin adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = AppLovinAdapterConfiguration

    /**
     * The AppLovin SDK needs a context for its privacy methods.
     */
    private var appContext: Context? = null

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

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
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> = withContext(IO) {
        PartnerLogController.log(SETUP_STARTED)

        return@withContext suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue("sdk_key"),
            ).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { sdkKey ->
                    context.applicationContext?.let {
                        // Save the application context for later usage.
                        appContext = it

                        appLovinSdk =
                            AppLovinSdk.getInstance(
                                sdkKey,
                                AppLovinSdkSettings(it),
                                it,
                            ).also { sdk ->
                                sdk.initializeSdk {
                                    sdk.mediationProvider = "Chartboost"
                                    sdk.setPluginVersion(configuration.adapterVersion)
                                    resumeOnce(
                                        Result.success(PartnerLogController.log(SETUP_SUCCEEDED)),
                                    )
                                }
                            }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "No SDK key found.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)),
                )
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
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
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
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        AppLovinPrivacySettings.setDoNotSell(!hasGrantedCcpaConsent, context)
    }

    /**
     * Notify AppLovin of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
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
        request: PreBidRequest,
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format.key) {
            AdFormat.INTERSTITIAL.key -> loadInterstitialAd(request, partnerAdListener)
            AdFormat.REWARDED.key -> loadRewardedAd(request, partnerAdListener)
            AdFormat.BANNER.key, "adaptive_banner" ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
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
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val partnerAdListener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format.key) {
            // Banner ads don't have their own show.
            AdFormat.BANNER.key, "adaptive_banner" -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL.key -> showInterstitialAd(activity, partnerAd, partnerAdListener)
            AdFormat.REWARDED.key -> showRewardedAd(activity, partnerAd, partnerAdListener)
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
        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            if (appLovinSdk == null) {
                PartnerLogController.log(LOAD_FAILED)
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_NOT_INITIALIZED)),
                )
                return@suspendCancellableCoroutine
            }

            AppLovinAdView(
                appLovinSdk,
                getAppLovinAdSize(request.size),
                request.partnerPlacement,
                context,
            ).apply {
                // Apply the Ad Load Listener to the AppLovinAdView
                setAdLoadListener(
                    object : AppLovinAdLoadListener {
                        override fun adReceived(ad: AppLovinAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = this@apply,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun failedToReceiveAd(errorCode: Int) {
                            PartnerLogController.log(LOAD_FAILED, "$errorCode")
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode))),
                            )
                        }
                    },
                )

                // Apply the AdView Event Listener to the AppLovinAdView
                setAdViewEventListener(
                    object : AppLovinAdViewEventListener {
                        override fun adOpenedFullscreen(
                            ad: AppLovinAd,
                            adView: AppLovinAdView,
                        ) {}

                        override fun adClosedFullscreen(
                            ad: AppLovinAd,
                            adView: AppLovinAdView,
                        ) {}

                        override fun adLeftApplication(
                            ad: AppLovinAd,
                            adView: AppLovinAdView,
                        ) {}

                        override fun adFailedToDisplay(
                            ad: AppLovinAd,
                            adView: AppLovinAdView,
                            errorCode: AppLovinAdViewDisplayErrorCode,
                        ) {
                            PartnerLogController.log(SHOW_FAILED, "$errorCode")
                        }
                    },
                )

                // Apply the Ad Click Listener to the AppLovinAdView
                setAdClickListener { ad ->
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ),
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            appLovinSdk?.let {
                // Save listener for later usage.
                listeners[request.identifier] = partnerAdListener

                it.adService.loadNextAdForZoneId(
                    request.partnerPlacement,
                    object : AppLovinAdLoadListener {
                        override fun adReceived(ad: AppLovinAd?) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = ad,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun failedToReceiveAd(errorCode: Int) {
                            PartnerLogController.log(LOAD_FAILED, "$errorCode")
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode))),
                            )
                        }
                    },
                )
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "AppLovin SDK instance is null.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_INSTANCE_NOT_FOUND)),
                )
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            appLovinSdk?.let {
                // Save listener for later usage.
                listeners[request.identifier] = partnerAdListener

                val rewardedAd =
                    AppLovinIncentivizedInterstitial.create(request.partnerPlacement, it)

                rewardedAd.preload(
                    object : AppLovinAdLoadListener {
                        override fun adReceived(ad: AppLovinAd?) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = ad,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun failedToReceiveAd(errorCode: Int) {
                            PartnerLogController.log(LOAD_FAILED, "$errorCode")
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(getChartboostMediationError(errorCode))),
                            )
                        }
                    },
                )
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "AppLovin SDK instance is null.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_INSTANCE_NOT_FOUND)),
                )
            }
        }
    }

    /**
     * Attempt to show an AppLovin interstitial ad.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        activity: Activity,
        partnerAd: PartnerAd,
        partnerAdListener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return (partnerAd.ad as? AppLovinAd)?.let {
            suspendCancellableCoroutine { continuation ->
                val interstitialAd =
                    AppLovinInterstitialAd.create(appLovinSdk, activity)

                interstitialAd.setAdDisplayListener(
                    object :
                        AppLovinAdDisplayListener {
                        override fun adDisplayed(ad: AppLovinAd?) {
                            PartnerLogController.log(SHOW_SUCCEEDED)
                            if (continuation.isActive) {
                                continuation.resume(Result.success(partnerAd))
                            }
                        }

                        override fun adHidden(ad: AppLovinAd?) {
                            PartnerLogController.log(DID_DISMISS)
                            partnerAdListener?.onPartnerAdDismissed(partnerAd, null)
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onPartnerAdDismissed for AppLovin adapter.",
                                )
                        }
                    },
                )

                interstitialAd.setAdClickListener {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
                        CUSTOM,
                        "Unable to fire onPartnerAdClicked for AppLovin adapter.",
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
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        activity: Activity,
        partnerAd: PartnerAd,
        partnerAdListener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val rewardedAd = AppLovinIncentivizedInterstitial.create(appLovinSdk)

            var isUserVerified: Boolean? = null

            val rewardListener: AppLovinAdRewardListener =
                object : AppLovinAdRewardListener {
                    override fun userRewardVerified(
                        appLovinAd: AppLovinAd,
                        map: Map<String, String>,
                    ) {
                        // user should be granted the reward upon completion
                        isUserVerified = true
                    }

                    override fun userOverQuota(
                        appLovinAd: AppLovinAd,
                        map: Map<String, String>?,
                    ) {}

                    override fun userRewardRejected(
                        appLovinAd: AppLovinAd,
                        map: Map<String, String>?,
                    ) {
                        // user has been denylisted and should not be granted a reward
                        isUserVerified = false
                    }

                    override fun validationRequestFailed(
                        appLovinAd: AppLovinAd,
                        responseCode: Int,
                    ) {
                        // user could not be verified
                        PartnerLogController.log(
                            CUSTOM,
                            "validationRequestFailed for $partnerAd. Error: $responseCode",
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
                        fullyWatched: Boolean,
                    ) {
                        // Only stop the reward if the user was explicity deny listed by AppLovin
                        when {
                            isUserVerified == false -> {
                                PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to reward due to user being denylisted by AppLovin.",
                                )
                            }

                            !fullyWatched -> {
                                PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to reward due to video not being fully watched.",
                                )
                            }

                            else -> {
                                PartnerLogController.log(DID_REWARD)
                                partnerAdListener?.onPartnerAdRewarded(partnerAd)
                                    ?: PartnerLogController.log(
                                        CUSTOM,
                                        "Unable to fire onPartnerAdRewarded for AppLovin adapter.",
                                    )
                            }
                        }
                    }
                }

            val displayListener: AppLovinAdDisplayListener =
                object : AppLovinAdDisplayListener {
                    override fun adDisplayed(appLovinAd: AppLovinAd) {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        if (continuation.isActive) {
                            continuation.resume(Result.success(partnerAd))
                        }
                        // TODO: HB-4119: We may need to check if the impression is recorded here.
                    }

                    override fun adHidden(appLovinAd: AppLovinAd) {
                        PartnerLogController.log(DID_DISMISS)
                        partnerAdListener?.onPartnerAdDismissed(partnerAd, null)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdDismissed for AppLovin adapter.",
                            )
                    }
                }

            val clickListener =
                AppLovinAdClickListener {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
                        CUSTOM,
                        "Unable to fire onPartnerAdClicked for AppLovin adapter.",
                    )
                }

            rewardedAd.show(
                partnerAd.ad as AppLovinAd,
                activity,
                rewardListener,
                playbackListener,
                displayListener,
                clickListener,
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
    private fun getChartboostMediationError(error: Int) =
        when (error) {
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
