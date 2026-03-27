package io.mobibox.pay

import androidx.fragment.app.FragmentActivity

/**
 * Main entry point for the Mobibox Payment SDK.
 *
 * Usage:
 * ```kotlin
 * val mobiPay = MobiPay()
 * mobiPay.initialize(
 *     checkoutHost = "https://pay.example.com",
 *     merchantKey  = "your-merchant-key",
 *     password     = "your-merchant-password",
 * )
 *
 * mobiPay.startPayment(
 *     activity         = this,
 *     orderNumber      = "order-123",
 *     orderAmount      = "10.00",
 *     orderCurrency    = "USD",
 *     orderDescription = "Test purchase",
 *     onTransactionComplete = { result -> /* handle success */ },
 *     onTransactionFailed   = { result -> /* handle failure */ },
 * )
 * ```
 */
class MobiPay {

    internal var checkoutHost: String = ""
        private set
    private var merchantKey: String = ""
    private var password: String = ""

    private var sessionTokenService: MobiPaySessionToken? = null
    internal var paymentProcessor: MobiPayPaymentProcessor? = null
        private set

    private var isInitialized = false

    /**
     * Initialize the SDK with your Mobibox credentials.
     *
     * Must be called before [startPayment] or [getSessionToken].
     *
     * @param checkoutHost Base URL of your Mobibox checkout (e.g. `https://pay.mobibox.io`).
     * @param merchantKey  Your merchant key.
     * @param password     Your merchant password.
     */
    fun initialize(
        checkoutHost: String,
        merchantKey: String,
        password: String,
    ) {
        this.checkoutHost = checkoutHost.trimEnd('/')
        this.merchantKey = merchantKey
        this.password = password

        sessionTokenService = MobiPaySessionToken(
            checkoutHost = this.checkoutHost,
            merchantKey = merchantKey,
            password = password,
        )
        paymentProcessor = MobiPayPaymentProcessor(
            checkoutHost = this.checkoutHost,
            merchantKey = merchantKey,
            password = password,
        )
        isInitialized = true
    }

    /**
     * Obtain a session token for Hosted Payment Fields.
     *
     * This is a **suspend** function — call it from a coroutine.
     *
     * @throws MobiPayException if the SDK is not initialized or the API call fails.
     */
    suspend fun getSessionToken(
        orderNumber: String,
        orderAmount: String,
        orderCurrency: String,
        orderDescription: String,
        recurringInit: Boolean? = null,
        scheduleId: String? = null,
        reqToken: Boolean? = null,
        cardToken: List<String>? = null,
    ): String {
        val service = sessionTokenService
            ?: throw MobiPayException("MobiPay not initialized. Call initialize() first.")

        return service.getSessionToken(
            orderNumber = orderNumber,
            orderAmount = orderAmount,
            orderCurrency = orderCurrency,
            orderDescription = orderDescription,
            recurringInit = recurringInit,
            scheduleId = scheduleId,
            reqToken = reqToken,
            cardToken = cardToken,
        )
    }

    /**
     * Start the full payment flow.
     *
     * Opens a BottomSheet containing the Hosted Payment Fields form,
     * handles 3DS redirects and status polling, and shows success / error screens.
     *
     * @param activity             The hosting [FragmentActivity].
     * @param orderNumber          Unique order number.
     * @param orderAmount          Order amount (e.g. `"10.00"`).
     * @param orderCurrency        ISO 4217 currency code (e.g. `"USD"`).
     * @param orderDescription     Human-readable order description.
     * @param cardholderName       Pre-filled cardholder name (optional).
     * @param email                Customer email (optional).
     * @param onSubmitResult       Called when the payment form is submitted.
     * @param onTransactionInitiated Called when transaction is initiated (all statuses).
     * @param onTransactionComplete Called when the transaction succeeds (settled).
     * @param onTransactionFailed  Called when the transaction fails (decline, etc.).
     * @param onSuccessRedirect    Called on success redirect URL.
     * @param onErrorRedirect      Called on error redirect URL.
     * @param onError              Called on any exception.
     * @param onPaywallClosed      Called when the payment sheet is dismissed.
     * @param billingAddress       Billing address map (optional).
     * @param browserInfo          Browser info map (optional).
     * @param birthDay             Birth day `YYYY-MM-DD` (optional).
     * @param selectedLanguage     Language code (optional).
     * @param recurringInit        Initialize recurring payment (optional).
     * @param scheduleId           Schedule ID for recurring (optional).
     * @param reqToken             Request card token (optional).
     * @param cardToken            List of card tokens for returning customers (optional).
     */
    fun startPayment(
        activity: FragmentActivity,
        orderNumber: String,
        orderAmount: String,
        orderCurrency: String,
        orderDescription: String,
        cardholderName: String? = null,
        email: String? = null,
        onSubmitResult: ((Map<String, Any?>) -> Unit)? = null,
        onTransactionInitiated: ((MobiPayTransactionResult) -> Unit)? = null,
        onTransactionComplete: ((MobiPayTransactionResult) -> Unit)? = null,
        onTransactionFailed: ((MobiPayTransactionResult) -> Unit)? = null,
        onSuccessRedirect: ((String) -> Unit)? = null,
        onErrorRedirect: ((String) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null,
        onPaywallClosed: (() -> Unit)? = null,
        billingAddress: Map<String, Any?>? = null,
        browserInfo: Map<String, Any?>? = null,
        birthDay: String? = null,
        selectedLanguage: String? = null,
        recurringInit: Boolean? = null,
        scheduleId: String? = null,
        reqToken: Boolean? = null,
        cardToken: List<String>? = null,
    ) {
        if (!isInitialized) {
            onError?.invoke(MobiPayException("MobiPay not initialized. Call initialize() first."))
            return
        }

        val sheet = MobiPayCheckoutSheet.newInstance().apply {
            this.mobiPay = this@MobiPay
            this.orderNumber = orderNumber
            this.orderAmount = orderAmount
            this.orderCurrency = orderCurrency
            this.orderDescription = orderDescription
            this.cardholderName = cardholderName
            this.email = email
            this.billingAddress = billingAddress
            this.browserInfo = browserInfo
            this.birthDay = birthDay
            this.selectedLanguage = selectedLanguage
            this.recurringInit = recurringInit
            this.scheduleId = scheduleId
            this.reqToken = reqToken
            this.cardToken = cardToken
            this.onSubmitResult = onSubmitResult
            this.onTransactionInitiated = onTransactionInitiated
            this.onTransactionComplete = onTransactionComplete
            this.onTransactionFailed = onTransactionFailed
            this.onSuccessRedirect = onSuccessRedirect
            this.onErrorRedirect = onErrorRedirect
            this.onError = onError
            this.onPaywallClosed = onPaywallClosed
        }

        sheet.show(activity.supportFragmentManager, "MobiPayCheckout")
    }
}
