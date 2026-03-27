package io.mobibox.pay

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

/**
 * BottomSheet that orchestrates the full payment flow:
 * 1. Fetches a session token
 * 2. Displays Hosted Payment Fields (card form)
 * 3. Processes the payment
 * 4. Shows success / error screens
 *
 * Presented by [MobiPay.startPayment].
 */
class MobiPayCheckoutSheet : BottomSheetDialogFragment() {

    // ── Parameters (set via companion factory) ──────────────────────────

    internal var mobiPay: MobiPay? = null
    internal var orderNumber: String = ""
    internal var orderAmount: String = ""
    internal var orderCurrency: String = ""
    internal var orderDescription: String = ""
    internal var cardholderName: String? = null
    internal var email: String? = null
    internal var billingAddress: Map<String, Any?>? = null
    internal var browserInfo: Map<String, Any?>? = null
    internal var birthDay: String? = null
    internal var selectedLanguage: String? = null
    internal var recurringInit: Boolean? = null
    internal var scheduleId: String? = null
    internal var reqToken: Boolean? = null
    internal var cardToken: List<String>? = null

    internal var onSubmitResult: ((Map<String, Any?>) -> Unit)? = null
    internal var onTransactionInitiated: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onTransactionComplete: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onTransactionFailed: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onSuccessRedirect: ((String) -> Unit)? = null
    internal var onErrorRedirect: ((String) -> Unit)? = null
    internal var onError: ((Exception) -> Unit)? = null
    internal var onPaywallClosed: (() -> Unit)? = null

    // ── Views ────────────────────────────────────────────────────────────

    private lateinit var webView: MobiPayHPFWebView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var thankYouOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var sessionErrorOverlay: LinearLayout
    private lateinit var sessionErrorMessage: TextView
    private lateinit var closeButton: ImageButton

    private var isPaymentInProgress = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock orientation to prevent rotation from destroying the payment form.
        // The user's card data and payment state cannot survive a config change,
        // so we lock to the current orientation while the sheet is showing.
        activity?.let {
            previousOrientation = it.requestedOrientation
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }

        if (savedInstanceState != null && mobiPay == null) {
            val existing = MobiPay.instance
            if (existing != null) {
                mobiPay = existing
            } else {
                dismissAllowingStateLoss()
                return
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        dialog.setOnShowListener { dlg ->
            val sheet = (dlg as BottomSheetDialog)
                .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behaviour = BottomSheetBehavior.from(it)
                it.layoutParams.height =
                    (resources.displayMetrics.heightPixels * 0.55).toInt()
                behaviour.state = BottomSheetBehavior.STATE_EXPANDED
                behaviour.skipCollapsed = true
                behaviour.isDraggable = false
            }
        }
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.mobipay_checkout_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore order parameters if this is a configuration change
        savedInstanceState?.let { bundle ->
            if (orderNumber.isEmpty()) orderNumber = bundle.getString(KEY_ORDER_NUMBER, "")
            if (orderAmount.isEmpty()) orderAmount = bundle.getString(KEY_ORDER_AMOUNT, "")
            if (orderCurrency.isEmpty()) orderCurrency = bundle.getString(KEY_ORDER_CURRENCY, "")
            if (orderDescription.isEmpty()) orderDescription = bundle.getString(KEY_ORDER_DESCRIPTION, "")
            if (cardholderName == null) cardholderName = bundle.getString(KEY_CARDHOLDER_NAME)
            if (email == null) email = bundle.getString(KEY_EMAIL)
            if (birthDay == null) birthDay = bundle.getString(KEY_BIRTHDAY)
            if (selectedLanguage == null) selectedLanguage = bundle.getString(KEY_LANGUAGE)
        }

        if (mobiPay == null) {
            dismissAllowingStateLoss()
            return
        }

        webView = view.findViewById(R.id.mobipay_webview)
        loadingOverlay = view.findViewById(R.id.mobipay_loading_overlay)
        thankYouOverlay = view.findViewById(R.id.mobipay_thank_you_overlay)
        errorOverlay = view.findViewById(R.id.mobipay_error_overlay)
        errorMessage = view.findViewById(R.id.mobipay_error_message)
        sessionErrorOverlay = view.findViewById(R.id.mobipay_session_error_overlay)
        sessionErrorMessage = view.findViewById(R.id.mobipay_session_error_message)
        closeButton = view.findViewById(R.id.mobipay_close_button)

        closeButton.setOnClickListener { if (!isPaymentInProgress) dismiss() }

        view.findViewById<Button>(R.id.mobipay_error_close_button)
            .setOnClickListener { dismiss() }

        view.findViewById<Button>(R.id.mobipay_session_retry_button)
            .setOnClickListener { createSessionAndLoad() }

        createSessionAndLoad()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ORDER_NUMBER, orderNumber)
        outState.putString(KEY_ORDER_AMOUNT, orderAmount)
        outState.putString(KEY_ORDER_CURRENCY, orderCurrency)
        outState.putString(KEY_ORDER_DESCRIPTION, orderDescription)
        outState.putString(KEY_CARDHOLDER_NAME, cardholderName)
        outState.putString(KEY_EMAIL, email)
        outState.putString(KEY_BIRTHDAY, birthDay)
        outState.putString(KEY_LANGUAGE, selectedLanguage)
    }

    override fun onDestroyView() {
        scope.cancel()
        if (::webView.isInitialized) webView.destroy2()
        super.onDestroyView()
    }

    override fun dismiss() {
        // Restore the original orientation before dismissing
        activity?.requestedOrientation = previousOrientation
        super.dismiss()
        onPaywallClosed?.invoke()
    }

    override fun onDestroy() {
        // Safety net: restore orientation if dismiss() wasn't called directly
        activity?.requestedOrientation = previousOrientation
        super.onDestroy()
    }

    // ── Session token + HPF ─────────────────────────────────────────────

    private fun createSessionAndLoad() {
        MobiPayLogger.d("── createSessionAndLoad ──")
        MobiPayLogger.d("  Order: $orderNumber | $orderAmount $orderCurrency")
        MobiPayLogger.d("  Description: $orderDescription")
        showLoading()
        sessionErrorOverlay.visibility = View.GONE

        val pay = mobiPay ?: run {
            MobiPayLogger.e("  mobiPay is NULL")
            showSessionError("MobiPay not initialized")
            return
        }

        scope.launch {
            try {
                val token = pay.getSessionToken(
                    orderNumber = orderNumber,
                    orderAmount = orderAmount,
                    orderCurrency = orderCurrency,
                    orderDescription = orderDescription,
                    recurringInit = recurringInit,
                    scheduleId = scheduleId,
                    reqToken = reqToken,
                    cardToken = cardToken,
                )
                MobiPayLogger.d("  Session token obtained, loading HPF")
                wireWebView(token)
                hideLoading()
            } catch (e: Exception) {
                MobiPayLogger.e("  Session token FAILED: ${e.message}", e)
                showSessionError(e.message ?: "Failed to create session token")
            }
        }
    }

    private fun wireWebView(sessionToken: String) {
        MobiPayLogger.d("── wireWebView ──")
        val pay = mobiPay ?: run {
            MobiPayLogger.e("  wireWebView: mobiPay is NULL")
            return
        }
        webView.sessionToken = sessionToken
        webView.checkoutHost = pay.checkoutHost
        webView.cardholderName = cardholderName
        webView.email = email
        webView.billingAddress = billingAddress
        webView.browserInfo = browserInfo
        webView.birthDay = birthDay
        webView.selectedLanguage = selectedLanguage
        webView.paymentProcessor = pay.paymentProcessor

        webView.onSubmitResult = { data ->
            MobiPayLogger.d("  onSubmitResult callback")
            isPaymentInProgress = true
            updateDismissibility()
            onSubmitResult?.invoke(data)
        }
        webView.onTransactionInitiated = { result ->
            MobiPayLogger.d("  onTransactionInitiated: ${result.status}")
            onTransactionInitiated?.invoke(result)
        }
        webView.onTransactionComplete = { result ->
            MobiPayLogger.d("  onTransactionComplete: ${result.status} — showing thank you")
            isPaymentInProgress = false
            showThankYou()
            scope.launch {
                delay(1_000)
                if (isAdded) dismiss()
                onTransactionComplete?.invoke(result)
            }
        }
        webView.onTransactionFailed = { result ->
            MobiPayLogger.d("  onTransactionFailed: ${result.status} / ${result.declineMessage}")
            isPaymentInProgress = false
            updateDismissibility()
            onTransactionFailed?.invoke(result)
        }
        webView.onSuccessRedirect = { url ->
            MobiPayLogger.d("  onSuccessRedirect: $url")
            onSuccessRedirect?.invoke(url)
        }
        webView.onErrorRedirect = { url ->
            MobiPayLogger.d("  onErrorRedirect: $url")
            dismiss()
            onErrorRedirect?.invoke(url)
        }
        webView.onError = { e ->
            MobiPayLogger.e("  onError: ${e.message}")
            isPaymentInProgress = false
            showPaymentError(friendlyError(e))
            onError?.invoke(e)
        }
        webView.onPaymentProcessingStarted = {
            MobiPayLogger.d("  onPaymentProcessingStarted — showing loading")
            showLoading()
            webView.visibility = View.GONE
        }
        webView.onPaymentProcessingFinished = {
            MobiPayLogger.d("  onPaymentProcessingFinished — hiding loading")
            hideLoading()
            webView.visibility = View.VISIBLE
        }

        webView.loadHPF()
    }

    // ── UI helpers ───────────────────────────────────────────────────────

    private fun showLoading() {
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private fun showThankYou() {
        webView.visibility = View.GONE
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.GONE
        closeButton.visibility = View.GONE
        thankYouOverlay.visibility = View.VISIBLE
    }

    private fun showPaymentError(msg: String) {
        webView.visibility = View.GONE
        loadingOverlay.visibility = View.GONE
        thankYouOverlay.visibility = View.GONE
        errorMessage.text = msg
        errorOverlay.visibility = View.VISIBLE
        updateDismissibility()
    }

    private fun showSessionError(msg: String) {
        loadingOverlay.visibility = View.GONE
        sessionErrorMessage.text = "Error: $msg"
        sessionErrorOverlay.visibility = View.VISIBLE
    }

    private fun updateDismissibility() {
        closeButton.visibility = if (isPaymentInProgress) View.GONE else View.VISIBLE
        isCancelable = !isPaymentInProgress
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("declined") -> "Your payment was declined. Please try another card."
            msg.contains("insufficient") -> "Insufficient funds. Please try another card."
            msg.contains("network") || msg.contains("timeout") ->
                "Network error. Please check your connection and try again."
            msg.contains("cardholder name is required") -> "Cardholder name is required."
            else -> "Error processing your payment. Please try another card."
        }
    }

    companion object {
        private const val KEY_ORDER_NUMBER = "mobipay_order_number"
        private const val KEY_ORDER_AMOUNT = "mobipay_order_amount"
        private const val KEY_ORDER_CURRENCY = "mobipay_order_currency"
        private const val KEY_ORDER_DESCRIPTION = "mobipay_order_description"
        private const val KEY_CARDHOLDER_NAME = "mobipay_cardholder_name"
        private const val KEY_EMAIL = "mobipay_email"
        private const val KEY_BIRTHDAY = "mobipay_birthday"
        private const val KEY_LANGUAGE = "mobipay_language"

        internal fun newInstance() = MobiPayCheckoutSheet()
    }
}
