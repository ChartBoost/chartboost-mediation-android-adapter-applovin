package com.chartboost.helium.applovinadapter

import android.content.Context
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Size
import android.view.View.GONE
import com.applovin.adview.*
import com.applovin.sdk.*
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium AppLovin SDK adapter.
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
                        LogController.w("AppLovin test mode is disabled. No advertising id found.")
                        AppLovinSdk.getInstance(context).settings.testDeviceAdvertisingIds =
                            emptyList()
                    }
                }
            } else {
                AppLovinSdk.getInstance(context).settings.testDeviceAdvertisingIds = emptyList()
            }

            LogController.d(
                "$TAG - AppLovin test mode is ${
                    if (enabled) "enabled. Remember to disable it before publishing."
                    else "disabled."
                }"
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
            LogController.d("$TAG - AppLovin verbose logging is ${if (enabled) "enabled" else "disabled"}.")
        }

        /**
         * The tag used for log messages.
         */
        private val TAG = "[${this::class.java.simpleName}]"
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
     * A map of Helium's listeners for the corresponding Helium placements.
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
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_APPLOVIN_ADAPTER_VERSION

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
        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials["sdk_key"]?.let { sdkKey ->
                context.applicationContext?.let {
                    // Save the application context for later usage.
                    appContext = it

                    appLovinSdk = AppLovinSdk.getInstance(
                        sdkKey,
                        AppLovinSdkSettings(it),
                        it
                    ).also { sdk ->
                        sdk.initializeSdk {
                            sdk.mediationProvider = "Helium"
                            sdk.setPluginVersion(adapterVersion)
                            continuation.resume(
                                Result.success(
                                    LogController.i("$TAG AppLovin SDK successfully initialized.")
                                )
                            )
                        }
                    }
                }

            } ?: run {
                LogController.e("Failed to initialize AppLovin SDK.")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
            }
        }
    }

    /**
     * AppLovin does not have a public method as to whether GDPR applies. No action required.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        // NO-OP
    }

    /**
     * Notify AppLovin of user GDPR consent.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        val consentGiven = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        AppLovinPrivacySettings.setHasUserConsent(consentGiven, context)
    }

    /**
     * Notify AppLovin of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        AppLovinPrivacySettings.setDoNotSell(!hasGivenCcpaConsent, context)
    }

    /**
     * Notify AppLovin of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
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
    ): Map<String, String> = emptyMap()

    /**
     * Attempt to load an AppLovin ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitial(request, partnerAdListener)
            AdFormat.REWARDED -> loadRewarded(request, partnerAdListener)
            AdFormat.BANNER -> loadBanner(
                request,
                context,
                partnerAdListener
            )
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
        val heliumListener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads don't have their own show.
            AdFormat.BANNER -> Result.success(partnerAd)
            AdFormat.INTERSTITIAL -> showInterstitialAd(context, partnerAd, heliumListener)
            AdFormat.REWARDED -> showRewardedAd(context, partnerAd, heliumListener)
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
        listeners.remove(partnerAd.request.heliumPlacement)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> Result.success(partnerAd)
        }
    }

    /**
     * Attempt to load an AppLovin banner ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBanner(
        request: AdLoadRequest,
        context: Context,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            AppLovinAdView(
                getAppLovinAdSize(request.size),
                request.partnerPlacement,
                context
            ).apply {
                // Apply the Ad Load Listener to the AppLovinAdView
                setAdLoadListener(object : AppLovinAdLoadListener {
                    override fun adReceived(ad: AppLovinAd) {
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
                        LogController.d("$TAG Banner ad failedToReceiveAd $errorCode")
                        continuation.resume(
                            Result.failure(HeliumAdException(getHeliumErrorCode(errorCode)))
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
                        LogController.d("$TAG Banner AppLovinAdViewDisplayErrorCode $errorCode")
                    }
                })

                // Apply the Ad Click Listener to the AppLovinAdView
                setAdClickListener { ad ->
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
     * @param request An [AdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitial(
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            appLovinSdk?.let {
                // Save listener for later usage.
                listeners[request.heliumPlacement] = partnerAdListener

                it.adService.loadNextAdForZoneId(
                    request.partnerPlacement,
                    object : AppLovinAdLoadListener {
                        override fun adReceived(ad: AppLovinAd?) {
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
                            LogController.d("$TAG interstitial failedToReceiveAd $errorCode")
                            continuation.resume(
                                Result.failure(HeliumAdException(getHeliumErrorCode(errorCode)))
                            )
                        }
                    }
                )
            }
        } ?: run {
            LogController.w("$TAG Failed to show AppLovin interstitial ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Attempt to load an AppLovin rewarded ad.
     *
     * @param request The [AdLoadRequest] containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewarded(
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            appLovinSdk?.let {

                // Save listener for later usage.
                listeners[request.heliumPlacement] = partnerAdListener

                val rewardedAd =
                    AppLovinIncentivizedInterstitial.create(request.partnerPlacement, it)

                rewardedAd.preload(object : AppLovinAdLoadListener {
                    override fun adReceived(ad: AppLovinAd?) {
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
                        LogController.d("$TAG rewarded failedToReceiveAd $errorCode")
                        continuation.resume(
                            Result.failure(HeliumAdException(getHeliumErrorCode(errorCode)))
                        )
                    }
                })
            }
        } ?: run {
            LogController.w("$TAG Failed to show AppLovin rewarded ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Attempt to show an AppLovin interstitial ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        context: Context,
        partnerAd: PartnerAd,
        heliumListener: PartnerAdListener?
    ): Result<PartnerAd> {
        return (partnerAd.ad as? AppLovinAd)?.let {
            suspendCoroutine { continuation ->
                val interstitialAd =
                    AppLovinInterstitialAd.create(appLovinSdk, context)

                interstitialAd.setAdDisplayListener(object :
                    AppLovinAdDisplayListener {
                    override fun adDisplayed(ad: AppLovinAd?) {
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun adHidden(ad: AppLovinAd?) {
                        heliumListener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdDismissed for AppLovin adapter."
                        )
                    }
                })

                interstitialAd.setAdClickListener {
                    heliumListener?.onPartnerAdClicked(partnerAd) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for AppLovin adapter."
                    )
                }

                interstitialAd.showAndRender(it)
            }
        } ?: run {
            LogController.w("$TAG Failed to show AppLovin interstitial ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Attempt to show an AppLovin rewarded ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        context: Context,
        partnerAd: PartnerAd,
        heliumListener: PartnerAdListener?
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val rewardedAd = AppLovinIncentivizedInterstitial.create(appLovinSdk)

            val rewardListener: AppLovinAdRewardListener = object : AppLovinAdRewardListener {
                override fun userRewardVerified(appLovinAd: AppLovinAd, map: Map<String, String>) {
                    heliumListener?.onPartnerAdRewarded(
                        partnerAd,
                        Reward(map["amount"]?.toInt() ?: 0, map["currency"].toString())
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdRewarded for AppLovin adapter."
                    )
                }

                override fun userOverQuota(appLovinAd: AppLovinAd, map: Map<String, String>?) {}
                override fun userRewardRejected(
                    appLovinAd: AppLovinAd,
                    map: Map<String, String>?
                ) {
                }

                override fun validationRequestFailed(appLovinAd: AppLovinAd, responseCode: Int) {
                    LogController.d("$TAG validationRequestFailed for $partnerAd. Error: $responseCode")
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
                        TODO("HB-4119: Need to check if we need to add logic here for rewards.")
                    }
                }

            val displayListener: AppLovinAdDisplayListener = object : AppLovinAdDisplayListener {
                override fun adDisplayed(appLovinAd: AppLovinAd) {
                    continuation.resume(Result.success(partnerAd))
                    TODO("HB-4119: We may need to check if the impression is recorded here.")
                }

                override fun adHidden(appLovinAd: AppLovinAd) {
                    heliumListener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdDismissed for AppLovin adapter."
                    )
                }
            }

            val clickListener =
                AppLovinAdClickListener {
                    heliumListener?.onPartnerAdClicked(partnerAd) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for AppLovin adapter."
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
            LogController.w("$TAG Failed to show AppLovin rewarded ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                Result.success(partnerAd)
            } else {
                LogController.w("$TAG Failed to destroy AppLovin banner ad. Ad is not an AppLovinAdView.")
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.w("$TAG Failed to destroy AppLovin banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Convert a given AppLovin error code into a [HeliumErrorCode].
     *
     * @param error The AppLovin error code as an [Int].
     *
     * @return The corresponding [HeliumErrorCode].
     */
    private fun getHeliumErrorCode(error: Int): HeliumErrorCode {
        return when (error) {
            AppLovinErrorCodes.NO_FILL -> HeliumErrorCode.NO_FILL
            AppLovinErrorCodes.NO_NETWORK -> HeliumErrorCode.NO_CONNECTIVITY
            AppLovinErrorCodes.SDK_DISABLED -> HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED
            // AppLovin is currently not part of programmatic bidding with Helium. Only waterfall.
            AppLovinErrorCodes.INVALID_AD_TOKEN -> HeliumErrorCode.INVALID_BID_PAYLOAD
            // AppLovin error codes that need to be properly mapped. Currently mapped to PARTNER_ERROR.
            AppLovinErrorCodes.UNABLE_TO_RENDER_AD,
            AppLovinErrorCodes.INVALID_ZONE,
            AppLovinErrorCodes.UNSPECIFIED_ERROR,
            AppLovinErrorCodes.INCENTIVIZED_NO_AD_PRELOADED,
            AppLovinErrorCodes.INVALID_RESPONSE,
            AppLovinErrorCodes.UNABLE_TO_PRECACHE_RESOURCES,
            AppLovinErrorCodes.UNABLE_TO_PRECACHE_IMAGE_RESOURCES,
            AppLovinErrorCodes.UNABLE_TO_PRECACHE_VIDEO_RESOURCES,
            AppLovinErrorCodes.FETCH_AD_TIMEOUT,
            AppLovinErrorCodes.INCENTIVIZED_UNKNOWN_SERVER_ERROR,
            AppLovinErrorCodes.INCENTIVIZED_SERVER_TIMEOUT,
            AppLovinErrorCodes.INCENTIVIZED_USER_CLOSED_VIDEO,
            AppLovinErrorCodes.INVALID_URL -> HeliumErrorCode.PARTNER_ERROR
            // In case of unknown AppLovin error codes.
            else -> HeliumErrorCode.INTERNAL
        }
    }
}
