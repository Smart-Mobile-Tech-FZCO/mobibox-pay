package io.mobibox.pay

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
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

    override fun onDestroyView() {
        scope.cancel()
        webView.destroy2()
        super.onDestroyView()
    }

    override fun dismiss() {
        super.dismiss()
        onPaywallClosed?.invoke()
    }

    // ── Session token + HPF ─────────────────────────────────────────────

    private fun createSessionAndLoad() {
        showLoading()
        sessionErrorOverlay.visibility = View.GONE

        val pay = mobiPay ?: run {
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
                wireWebView(token)
                hideLoading()
            } catch (e: Exception) {
                showSessionError(e.message ?: "Failed to create session token")
            }
        }
    }

    private fun wireWebView(sessionToken: String) {
        val pay = mobiPay ?: return
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
            isPaymentInProgress = true
            updateDismissibility()
            onSubmitResult?.invoke(data)
        }
        webView.onTransactionInitiated = onTransactionInitiated
        webView.onTransactionComplete = { result ->
            isPaymentInProgress = false
            showThankYou()
            scope.launch {
                delay(1_000)
                if (isAdded) dismiss()
                onTransactionComplete?.invoke(result)
            }
        }
        webView.onTransactionFailed = { result ->
            isPaymentInProgress = false
            updateDismissibility()
            onTransactionFailed?.invoke(result)
        }
        webView.onSuccessRedirect = onSuccessRedirect
        webView.onErrorRedirect = { url ->
            dismiss()
            onErrorRedirect?.invoke(url)
        }
        webView.onError = { e ->
            isPaymentInProgress = false
            showPaymentError(friendlyError(e))
            onError?.invoke(e)
        }
        webView.onPaymentProcessingStarted = {
            showLoading()
            webView.visibility = View.GONE
        }
        webView.onPaymentProcessingFinished = {
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
        internal fun newInstance() = MobiPayCheckoutSheet()
    }
}
