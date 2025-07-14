/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.applovinadapter

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Size
import android.view.View.GONE
import com.applovin.adview.AppLovinAdView
import com.applovin.adview.AppLovinAdViewDisplayErrorCode
import com.applovin.adview.AppLovinAdViewEventListener
import com.applovin.adview.AppLovinIncentivizedInterstitial
import com.applovin.adview.AppLovinInterstitialAd
import com.applovin.sdk.*
import com.chartboost.chartboostmediationsdk.ad.ChartboostMediationBannerAdView.ChartboostMediationBannerSize.Companion.asSize
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentManagementPlatform
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
    ): Result<Map<String, Any>> = withContext(IO) {
        PartnerLogController.log(SETUP_STARTED)

        suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
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

                        // Generate list of test device advertising IDs
                        val adIds =
                            try {
                                AdvertisingIdClient.getAdvertisingIdInfo(context).id
                            } catch (e: Exception) {
                                context.contentResolver.let { resolver ->
                                    Settings.Secure.getString(resolver, "advertising_id")
                                }
                            }?.takeIf { (configuration as AppLovinAdapterConfiguration).testMode }?.let { listOf(it) } ?: emptyList()

                        // Build the configuration
                        val appLovinConfiguration = AppLovinSdkInitializationConfiguration.builder(sdkKey)
                            .setMediationProvider("Chartboost")
                            .setPluginVersion(configuration.adapterVersion)
                            .setTestDeviceAdvertisingIds(adIds)
                            .build()

                        // Get the SDK instance.
                        val appLovinSdk = AppLovinSdk.getInstance(context)
                        AppLovinAdapter.appLovinSdk = appLovinSdk

                        // Finally initialize it.
                        appLovinSdk.initialize(appLovinConfiguration) {
                            PartnerLogController.log(SETUP_SUCCEEDED)
                            resumeOnce(
                                Result.success(emptyMap()),
                            )
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "No SDK key found.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials)),
                )
            }
        }
    }

    /**
     * Notify AppLovin if the user is underage.
     * Note: COPPA is no longer supported in the AppLovin SDK 13.0.0
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is underage, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(CUSTOM, "COPPA is not supported with AppLovin")
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
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

        return when (request.format) {
            PartnerAdFormats.INTERSTITIAL -> loadInterstitialAd(request, partnerAdListener)
            PartnerAdFormats.REWARDED -> loadRewardedAd(request, partnerAdListener)
            PartnerAdFormats.BANNER ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
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

        return when (partnerAd.request.format) {
            // Banner ads don't have their own show.
            PartnerAdFormats.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL -> showInterstitialAd(activity, partnerAd, partnerAdListener)
            PartnerAdFormats.REWARDED -> showRewardedAd(activity, partnerAd, partnerAdListener)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        val consent = consents[configuration.partnerId]?.takeIf { it.isNotBlank() }
            ?: consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.takeIf { it.isNotBlank() }
        consent?.let {
            if (it == ConsentValues.DOES_NOT_APPLY) {
                return@let
            }
            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )
            val userConsented = it == ConsentValues.GRANTED
            AppLovinPrivacySettings.setHasUserConsent(userConsented, context)
        }

        val hasGrantedUspConsent =
            consents[ConsentKeys.CCPA_OPT_IN]?.takeIf { it.isNotBlank() }
                ?.equals(ConsentValues.GRANTED)
                ?: consents[ConsentKeys.USP]?.takeIf { it.isNotBlank() }
                    ?.let { ConsentManagementPlatform.getUspConsentFromUspString(it) }
        hasGrantedUspConsent?.let {
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )
            AppLovinPrivacySettings.setDoNotSell(!hasGrantedUspConsent, context)
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
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.PartnerNotInitialized)),
                )
                return@suspendCancellableCoroutine
            }

            AppLovinAdView(
                appLovinSdk,
                getAppLovinAdSize(request.bannerSize?.asSize()),
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
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.PartnerInstanceNotFound)),
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
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.PartnerInstanceNotFound)),
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
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
            AppLovinErrorCodes.NO_FILL -> ChartboostMediationError.LoadError.NoFill
            AppLovinErrorCodes.NO_NETWORK -> ChartboostMediationError.OtherError.NoConnectivity
            AppLovinErrorCodes.SDK_DISABLED -> ChartboostMediationError.InitializationError.Skipped
            // AppLovin is currently not part of programmatic bidding with Chartboost Mediation. Only waterfall.
            AppLovinErrorCodes.INVALID_AD_TOKEN -> ChartboostMediationError.LoadError.AuctionNoBid
            AppLovinErrorCodes.UNABLE_TO_RENDER_AD -> ChartboostMediationError.ShowError.Unknown
            AppLovinErrorCodes.FETCH_AD_TIMEOUT -> ChartboostMediationError.LoadError.AdRequestTimeout
            AppLovinErrorCodes.UNABLE_TO_PRECACHE_RESOURCES, AppLovinErrorCodes.UNABLE_TO_PRECACHE_VIDEO_RESOURCES, AppLovinErrorCodes.UNABLE_TO_PRECACHE_IMAGE_RESOURCES -> ChartboostMediationError.LoadError.OutOfStorage
            AppLovinErrorCodes.INCENTIVIZED_NO_AD_PRELOADED -> ChartboostMediationError.ShowError.AdNotReady
            AppLovinErrorCodes.INVALID_RESPONSE -> ChartboostMediationError.LoadError.InvalidBidResponse
            AppLovinErrorCodes.INVALID_ZONE -> ChartboostMediationError.LoadError.InvalidPartnerPlacement
            else -> ChartboostMediationError.OtherError.PartnerError
        }
}
