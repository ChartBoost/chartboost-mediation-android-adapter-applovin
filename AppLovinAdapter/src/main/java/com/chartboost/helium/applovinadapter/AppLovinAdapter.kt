package com.chartboost.helium.applovinadapter

import android.content.Context
import android.util.DisplayMetrics
import android.util.Size
import android.view.View.GONE
import com.applovin.adview.*
import com.applovin.sdk.*
import com.chartboost.helium.applovinadapter.BuildConfig.VERSION_NAME
import com.chartboost.heliumsdk.HeliumSdk
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium AppLovin SDK adapter.
 */
class AppLovinAdapter : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private const val TAG = "[AppLovinAdapter]"
    }

    /**
     * The AppLovin SDK needs an instance that is later passed on its ad lifecycle methods.
     */
    private var appLovinSdk: AppLovinSdk? = null

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
        get() = VERSION_NAME

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
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        return suspendCoroutine { continuation ->
            partnerConfiguration.credentials["sdk_key"]?.let { appId ->
                appLovinSdk = AppLovinSdk.getInstance(
                    appId,
                    AppLovinSdkSettings(context.applicationContext),
                    context.applicationContext
                ).also { sdk ->
                    sdk.initializeSdk {
                        sdk.mediationProvider = "Helium"
                        sdk.setPluginVersion(VERSION_NAME)
                        LogController.i("$TAG AppLovin SDK successfully initialized.")
                        continuation.resume(Result.success(Unit))
                    }
                }
            } ?: run {
                LogController.e("Failed to create AppLovin SDK")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
            }
        }
    }

    /**
     * AppLovin does not have a public method as to whether GDPR applies. No action required.
     */
    override fun setGdprApplies(gdprApplies: Boolean) {
        // NO-OP
    }

    /**
     * Notify AppLovin of user GDPR consent.
     *
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(gdprConsentStatus: GdprConsentStatus) {
        val consentGiven = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        AppLovinPrivacySettings.setHasUserConsent(consentGiven, HeliumSdk.getContext())
    }

    /**
     * Notify AppLovin of the CCPA compliance.
     *
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaPrivacyString(privacyString: String?) {
        val doNotSell = privacyString?.length?.let {
            it.takeIf { it > 2 }?.let {
                when (privacyString[2]) {
                    'Y' -> true // The user opts out of the sale of personal data, which means they did not consent.
                    'N' -> false // The user opts in to the sale of personal data, which means they consent.
                    else -> {
                        // CCPA does not apply
                        return
                    }
                }
            } ?: return
        } ?: return

        AppLovinPrivacySettings.setDoNotSell(doNotSell, HeliumSdk.getContext())
    }

    /**
     * Notify AppLovin of the COPPA subjectivity.
     */
    override fun setUserSubjectToCoppa(isSubjectToCoppa: Boolean) {
        AppLovinPrivacySettings.setIsAgeRestrictedUser(isSubjectToCoppa, HeliumSdk.getContext())
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

        if (appLovinSdk == null) {
            LogController.w("Failed to load AppLovin ad, AppLovin SDK is null")
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
        }

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
            AdFormat.BANNER -> {
                // Banner ads don't have their own show.
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL -> showInterstitialAd(partnerAd, heliumListener)
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
     */
    private suspend fun loadBanner(
        request: AdLoadRequest,
        context: Context,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val appLovinAdView =
                AppLovinAdView(
                    appLovinSdk,
                    getAppLovinAdSize(request.size),
                    request.partnerId,
                    context
                )

            val loadAdListener: AppLovinAdLoadListener = object : AppLovinAdLoadListener {
                override fun adReceived(ad: AppLovinAd) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                inlineView = appLovinAdView,
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
            }

            appLovinAdView.setAdViewEventListener(object : AppLovinAdViewEventListener {
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

            appLovinAdView.setAdClickListener { ad ->
                partnerAdListener.onPartnerAdClicked(
                    PartnerAd(
                        ad = ad,
                        inlineView = appLovinAdView,
                        details = emptyMap(),
                        request = request
                    )
                )
            }

            val alSdkInstance = appLovinSdk

            if (alSdkInstance == null) {
                LogController.w("Failed to load AppLovin ad, AppLovin SDK is null")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                return@suspendCoroutine
            }

            alSdkInstance.adService.loadNextAdForZoneId(request.partnerPlacement, loadAdListener)
        }
    }

    /**
     * Find the most appropriate AppLovin ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The AppLovin ad size that best matches the given [Size].
     */
    private fun getAppLovinAdSize(size: Size?) = when (size?.height) {
        // TO-DO: An updated version of this method will later be updated.
        in 50 until 90 -> AppLovinAdSize.BANNER
        in 90 until 250 -> AppLovinAdSize.LEADER
        in 250 until DisplayMetrics().heightPixels -> AppLovinAdSize.MREC
        else -> AppLovinAdSize.BANNER
    }

    /**
     * Attempt to load an AppLovin interstitial ad.
     *
     * @param request An [AdLoadRequest] instance containing data to load the ad with.
     */
    private suspend fun loadInterstitial(
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save listener for later usage.
        listeners[request.heliumPlacement] = partnerAdListener

        // Let's check the AppLovin instance.
        val alSdkInstance = appLovinSdk

        if (alSdkInstance == null) {
            LogController.w("Failed to load AppLovin ad, AppLovin SDK is null")
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
        }

        return suspendCoroutine { continuation ->
            alSdkInstance.adService.loadNextAdForZoneId(
                request.heliumPlacement,
                object : AppLovinAdLoadListener {
                    override fun adReceived(ad: AppLovinAd?) {
                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = ad,
                                    inlineView = null,
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
    }

    /**
     * Attempt to load an AppLovin rewarded ad.
     *
     * @param request The [AdLoadRequest] containing relevant data for the current ad load call.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewarded(
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            // Save listener for later usage.
            listeners[request.heliumPlacement] = partnerAdListener

            val rewardedAd =
                AppLovinIncentivizedInterstitial.create(request.partnerPlacement, appLovinSdk)
            rewardedAd.preload(object : AppLovinAdLoadListener {
                override fun adReceived(ad: AppLovinAd?) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                inlineView = null,
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
    }

    /**
     * Attempt to show an AppLovin interstitial ad.
     *
     * @param partnerAd The [PartnerAd] object containing the AppLovin ad to be shown.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        partnerAd: PartnerAd,
        heliumListener: PartnerAdListener?
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            (partnerAd.ad as? AppLovinAd)?.let {
                val interstitialAd =
                    AppLovinInterstitialAd.create(appLovinSdk, HeliumSdk.getContext())

                interstitialAd.setAdDisplayListener(object :
                    AppLovinAdDisplayListener {
                    override fun adDisplayed(ad: AppLovinAd?) {
                        heliumListener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdImpression for AppLovin adapter."
                        )
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
                    continuation.resume(Result.success(partnerAd))
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

            val adRewardListener: AppLovinAdRewardListener = object : AppLovinAdRewardListener {
                override fun userRewardVerified(appLovinAd: AppLovinAd, map: Map<String, String>) {
                    map["amount"]?.let { amount ->
                        Reward(
                            amount.toInt(), map["currency"].toString()
                        )
                    }?.let { reward ->
                        heliumListener?.onPartnerAdRewarded(
                            partnerAd,
                            reward
                        )
                    } ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdRewarded for AppLovin adapter."
                    )
                }

                override fun userOverQuota(appLovinAd: AppLovinAd, map: Map<String, String>?) {}
                override fun userRewardRejected(appLovinAd: AppLovinAd, map: Map<String, String>?) {}

                override fun validationRequestFailed(appLovinAd: AppLovinAd, responseCode: Int) {
                    LogController.d("$TAG Failed to show AppLovin rewarded ad. Error: $responseCode")
                    continuation.resume(
                        Result.failure(HeliumAdException(getHeliumErrorCode(responseCode)))
                    )
                }
            }

            val adVideoPlaybackListener: AppLovinAdVideoPlaybackListener =
                object : AppLovinAdVideoPlaybackListener {
                    override fun videoPlaybackBegan(appLovinAd: AppLovinAd) {
                        heliumListener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdImpression for AppLovin adapter."
                        )
                    }

                    override fun videoPlaybackEnded(
                        appLovinAd: AppLovinAd,
                        percentViewed: Double,
                        fullyWatched: Boolean
                    ) {}
                }

            val adDisplayListener: AppLovinAdDisplayListener = object : AppLovinAdDisplayListener {
                override fun adDisplayed(appLovinAd: AppLovinAd) {
                    //We may need to check if the impression is recorded here.
                    continuation.resume(Result.success(partnerAd))
                }

                override fun adHidden(appLovinAd: AppLovinAd) {
                    heliumListener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdDismissed for AppLovin adapter."
                    )
                }
            }

            val adClickListener =
                AppLovinAdClickListener {
                    heliumListener?.onPartnerAdClicked(partnerAd) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for AppLovin adapter."
                    )
                }

            rewardedAd.show(
                partnerAd.ad as AppLovinAd,
                context,
                adRewardListener,
                adVideoPlaybackListener,
                adDisplayListener,
                adClickListener
            )
        } ?: run {
            LogController.w("$TAG Failed to show AppLovin interstitial ad. Ad is null.")
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
            else -> HeliumErrorCode.INTERNAL
        }
    }
}
