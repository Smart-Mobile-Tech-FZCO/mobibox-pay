package io.mobibox.pay

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

/**
 * WebView that loads and manages Mobibox Hosted Payment Fields (HPF).
 *
 * Handles:
 * - Rendering card input fields via HPF JS SDK
 * - Collecting card data and triggering payment processing
 * - 3DS redirects (GET and POST)
 * - Transaction status polling after 3DS
 */
@SuppressLint("SetJavaScriptEnabled")
class MobiPayHPFWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    // ── Configuration set by MobiPayCheckoutSheet ────────────────────────

    internal var sessionToken: String = ""
    internal var checkoutHost: String = ""
    internal var cardholderName: String? = null
    internal var email: String? = null
    internal var billingAddress: Map<String, Any?>? = null
    internal var browserInfo: Map<String, Any?>? = null
    internal var birthDay: String? = null
    internal var selectedLanguage: String? = null

    internal var paymentProcessor: MobiPayPaymentProcessor? = null

    // ── Callbacks ────────────────────────────────────────────────────────

    internal var onSubmitResult: ((Map<String, Any?>) -> Unit)? = null
    internal var onTransactionInitiated: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onTransactionComplete: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onTransactionFailed: ((MobiPayTransactionResult) -> Unit)? = null
    internal var onSuccessRedirect: ((String) -> Unit)? = null
    internal var onErrorRedirect: ((String) -> Unit)? = null
    internal var onError: ((Exception) -> Unit)? = null
    internal var onPaymentProcessingStarted: (() -> Unit)? = null
    internal var onPaymentProcessingFinished: (() -> Unit)? = null

    // ── Internal state ───────────────────────────────────────────────────

    private var hasHandledRedirect = false
    private var pendingTransactionResult: MobiPayTransactionResult? = null
    private var statusPollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        addJavascriptInterface(HPFBridge(), "HPFChannel")

        webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()

                if (url.startsWith("data:") || url == "about:blank") {
                    return false
                }

                val lower = url.lowercase()

                if (url.contains("mobipay.flutter.sdk") || url.contains("flutter.sdk")) {
                    if (handleRedirectUrl(url)) return true
                }

                val isReturn = lower.contains("/return") || lower.contains("return?")
                val isSuccess = lower.contains("/success") || lower.contains("success?")
                val isCancel = lower.contains("/cancel") || lower.contains("cancel?")
                val isError = lower.contains("/error") || lower.contains("error?")

                if ((isReturn || isSuccess || isCancel || isError) &&
                    (url.startsWith("http://") || url.startsWith("https://"))
                ) {
                    if (handleRedirectUrl(url)) return true
                }

                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                val lower = url.lowercase()
                val isIntermediate = lower.contains("/redirect/") ||
                        lower.contains("/interaction/") ||
                        lower.contains("/collector/")

                if (!hasHandledRedirect && !isIntermediate) {
                    val isReturnUrl = lower.contains("/return") ||
                            lower.contains("/success") ||
                            lower.contains("/cancel") ||
                            lower.contains("/error") ||
                            lower.contains("mobipay.flutter.sdk")

                    if (isReturnUrl) handleRedirectUrl(url)
                }
            }
        }
    }

    /** Load the HPF HTML into the WebView. */
    internal fun loadHPF() {
        val html = buildHPFHtml()
        loadDataWithBaseURL("$checkoutHost/", html, "text/html", "UTF-8", null)
    }

    // ── Redirect handling ────────────────────────────────────────────────

    private fun handleRedirectUrl(url: String): Boolean {
        if (hasHandledRedirect) return false

        val lower = url.lowercase()
        if (lower.contains("/redirect/") || lower.contains("/interaction/") || lower.contains("/collector/")) {
            return false
        }
        if (url.startsWith("data:") || url == "about:blank") return false

        try {
            val uri = Uri.parse(url)
            val paymentIdFromUrl = uri.getQueryParameter("payment_id")
                ?: uri.pathSegments.firstOrNull { it.contains("-") && it.length > 20 }

            if (!paymentIdFromUrl.isNullOrEmpty()) {
                val transId = uri.getQueryParameter("trans_id")
                val orderId = uri.getQueryParameter("order_id")
                val hash = uri.getQueryParameter("hash")

                pendingTransactionResult?.let { pending ->
                    pendingTransactionResult = pending.copy(
                        paymentId = paymentIdFromUrl,
                        publicId = pending.publicId ?: paymentIdFromUrl,
                        transactionId = transId ?: pending.transactionId,
                        orderId = orderId ?: pending.orderId,
                        hash = hash ?: pending.hash,
                    )
                }

                if (statusPollJob?.isActive != true) {
                    startStatusPolling(paymentIdFromUrl)
                }
            }
        } catch (_: Exception) { /* silently ignore */ }

        return false
    }

    // ── Payment processing ───────────────────────────────────────────────

    private fun processPayment(resultData: Map<String, Any?>) {
        val processor = paymentProcessor ?: return

        val name = (resultData["cardholderName"] as? String)?.trim()
            ?: cardholderName?.trim()

        if (name.isNullOrEmpty()) {
            resetButtonState()
            return
        }

        val result = resultData["result"] as? String
        if (result != "success") {
            resetButtonState()
            return
        }

        scope.launch {
            try {
                val paymentResponse = processor.processPayment(
                    sessionToken = sessionToken,
                    cardholderName = name,
                    email = email,
                    billingAddress = billingAddress,
                    browserInfo = browserInfo,
                    birthDay = birthDay,
                    selectedLanguage = selectedLanguage,
                )

                val txResult = buildTransactionResult(paymentResponse)
                onTransactionInitiated?.invoke(txResult)

                when (paymentResponse.result) {
                    "success" -> {
                        resetButtonState()
                        onPaymentProcessingFinished?.invoke()
                        onTransactionComplete?.invoke(txResult)
                        onSuccessRedirect?.invoke(paymentResponse.redirectUrl ?: "")
                    }

                    "decline" -> {
                        resetButtonState()
                        onPaymentProcessingFinished?.invoke()
                        onTransactionFailed?.invoke(txResult)
                        onError?.invoke(
                            MobiPayException(paymentResponse.declineMessage ?: "Payment declined"),
                        )
                    }

                    "redirect" -> {
                        pendingTransactionResult = txResult
                        handleRedirectResponse(paymentResponse, txResult)
                    }

                    "waiting" -> {
                        resetButtonState()
                        pendingTransactionResult = txResult
                        onPaymentProcessingStarted?.invoke()
                        val pollId = txResult.paymentId
                        if (!pollId.isNullOrEmpty()) {
                            startStatusPolling(pollId)
                        } else {
                            onError?.invoke(MobiPayException("Payment waiting but no payment ID"))
                        }
                    }

                    else -> {
                        resetButtonState()
                        onPaymentProcessingFinished?.invoke()
                        onTransactionFailed?.invoke(txResult)
                        onError?.invoke(
                            MobiPayException("Unknown payment result: ${paymentResponse.result}"),
                        )
                    }
                }
            } catch (e: Exception) {
                resetButtonState()
                onPaymentProcessingFinished?.invoke()
                onError?.invoke(MobiPayException("Error processing payment: ${e.message}", e))
            }
        }
    }

    private suspend fun handleRedirectResponse(
        response: PaymentProcessingResponse,
        txResult: MobiPayTransactionResult,
    ) {
        val redirectUrl = response.redirectUrl
        if (redirectUrl == null) {
            resetButtonState()
            onPaymentProcessingFinished?.invoke()
            onTransactionFailed?.invoke(txResult)
            onError?.invoke(MobiPayException("Redirect required but no redirect URL provided"))
            return
        }

        withContext(Dispatchers.Main) {
            if (response.requiresPostRedirect && response.redirectParams != null) {
                val formHtml = buildPostRedirectForm(redirectUrl, response.redirectParams)
                loadDataWithBaseURL(null, formHtml, "text/html", "UTF-8", null)
            } else {
                loadUrl(redirectUrl)
            }
        }

        val pollId = txResult.paymentId ?: txResult.publicId
        if (!pollId.isNullOrEmpty()) {
            startStatusPolling(pollId)
        }
    }

    // ── Status polling ───────────────────────────────────────────────────

    private fun startStatusPolling(paymentId: String) {
        val processor = paymentProcessor ?: return
        if (statusPollJob?.isActive == true) return

        var lastKnownStatus: String? = null

        statusPollJob = scope.launch {
            var pollCount = 0
            val maxPolls = 60

            while (isActive && !hasHandledRedirect && pollCount < maxPolls) {
                delay(5_000)
                pollCount++

                try {
                    val statusResponse = processor.getTransactionStatus(paymentId)
                    val currentStatus = statusResponse.status ?: "unknown"

                    if ((lastKnownStatus == "prepare" || lastKnownStatus == "3ds") &&
                        currentStatus != "prepare" && currentStatus != "3ds"
                    ) {
                        onPaymentProcessingStarted?.invoke()
                    }

                    lastKnownStatus = currentStatus

                    when (currentStatus) {
                        "settled" -> {
                            hasHandledRedirect = true
                            val successResult = buildStatusResult(
                                "success", statusResponse, paymentId,
                            )
                            withContext(Dispatchers.Main) {
                                onPaymentProcessingFinished?.invoke()
                                onTransactionComplete?.invoke(successResult)
                                onSuccessRedirect?.invoke(
                                    pendingTransactionResult?.redirectUrl ?: "",
                                )
                            }
                            return@launch
                        }

                        "decline", "refund", "reversal", "void", "chargeback" -> {
                            hasHandledRedirect = true
                            val failResult = buildStatusResult(
                                currentStatus, statusResponse, paymentId,
                            )
                            val msg = buildString {
                                append(statusResponse.statusDescription)
                                statusResponse.reason?.let { append(": $it") }
                            }
                            withContext(Dispatchers.Main) {
                                onPaymentProcessingFinished?.invoke()
                                onTransactionFailed?.invoke(failResult)
                                onError?.invoke(MobiPayException(msg))
                            }
                            return@launch
                        }

                        // prepare, pending, 3ds, redirect → keep polling
                    }
                } catch (_: Exception) {
                    // transient error — keep polling
                }
            }

            // Timeout
            if (!hasHandledRedirect) {
                hasHandledRedirect = true
                withContext(Dispatchers.Main) {
                    onPaymentProcessingFinished?.invoke()
                    onError?.invoke(
                        MobiPayException(
                            "Payment status check timed out. Please verify payment status manually.",
                        ),
                    )
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildTransactionResult(
        response: PaymentProcessingResponse,
    ): MobiPayTransactionResult {
        val qp = response.returnUrlQueryParams
        var pid = qp?.get("payment_id") as? String
        var tid = qp?.get("trans_id") as? String
        var oid = qp?.get("order_id") as? String
        var h = qp?.get("hash") as? String

        if (pid == null && !response.redirectParams.isNullOrEmpty()) {
            val combined = mutableMapOf<String, Any?>()
            response.redirectParams.forEach { combined.putAll(it) }
            pid = pid ?: combined["payment_id"] as? String
            tid = tid ?: combined["trans_id"] as? String
            oid = oid ?: combined["order_id"] as? String
            h = h ?: combined["hash"] as? String
        }

        pid = pid ?: response.publicId

        return MobiPayTransactionResult(
            status = response.result,
            publicId = response.publicId,
            paymentId = pid,
            transactionId = tid,
            orderId = oid,
            hash = h,
            declineMessage = response.declineMessage,
            redirectUrl = response.redirectUrl,
            redirectParams = qp,
            rawData = mapOf(
                "result" to response.result,
                "public_id" to response.publicId,
                "redirect_url" to response.redirectUrl,
                "redirect_params" to response.redirectParams,
                "return_url_query_params" to qp,
                "decline_message" to response.declineMessage,
            ),
        )
    }

    private fun buildStatusResult(
        status: String,
        response: TransactionStatusResponse,
        paymentId: String,
    ): MobiPayTransactionResult {
        val transId = (response.order?.get("trans_id") as? String)
            ?: (response.order?.get("transaction_id") as? String)
        val orderId = (response.order?.get("number") as? String)
            ?: (response.order?.get("order_id") as? String)
            ?: (response.order?.get("id") as? String)

        return MobiPayTransactionResult(
            status = status,
            publicId = pendingTransactionResult?.publicId ?: response.paymentId,
            paymentId = pendingTransactionResult?.paymentId ?: paymentId,
            transactionId = transId ?: pendingTransactionResult?.transactionId,
            orderId = orderId ?: pendingTransactionResult?.orderId,
            hash = pendingTransactionResult?.hash,
            redirectUrl = pendingTransactionResult?.redirectUrl,
            declineMessage = response.reason ?: response.statusDescription,
            redirectParams = pendingTransactionResult?.redirectParams,
            rawData = mapOf(
                "result" to status,
                "status" to response.status,
                "status_description" to response.statusDescription,
                "payment_id" to response.paymentId,
                "date" to response.date,
                "order" to response.order,
                "customer" to response.customer,
            ),
        )
    }

    private fun resetButtonState() {
        post {
            evaluateJavascript("window.resetPaymentButton && window.resetPaymentButton();", null)
        }
    }

    internal fun destroy2() {
        statusPollJob?.cancel()
        scope.cancel()
        removeJavascriptInterface("HPFChannel")
        super.destroy()
    }

    // ── JS ↔ Kotlin bridge ───────────────────────────────────────────────

    private inner class HPFBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            try {
                val data = JSONObject(message)
                when (data.optString("type")) {
                    "onSubmitResult" -> {
                        val resultData = data.getJSONObject("data").toMap()
                        post { onSubmitResult?.invoke(resultData) }

                        val result = resultData["result"] as? String
                        if (result == "success" && paymentProcessor != null) {
                            post {
                                onPaymentProcessingStarted?.invoke()
                                processPayment(resultData)
                            }
                        } else {
                            resetButtonState()
                        }
                    }

                    "onUrlChange" -> {
                        val url = data.optString("url")
                        if (url.isNotEmpty()) post { handleRedirectUrl(url) }
                    }

                    "onError" -> {
                        resetButtonState()
                        val errorMsg = data.optString("error", "Unknown error")
                        post { onError?.invoke(MobiPayException(errorMsg)) }
                    }
                }
            } catch (e: Exception) {
                post { onError?.invoke(MobiPayException("Error parsing message: ${e.message}", e)) }
            }
        }
    }

    // ── HTML builders ────────────────────────────────────────────────────

    private fun buildHPFHtml(): String {
        val tokenEscaped = sessionToken.replace("\\", "\\\\").replace("\"", "\\\"")
        val nameEscaped = (cardholderName ?: "")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <style>
    * { box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      margin: 0; padding: 0;
      background-color: #000000; color: #ffffff;
    }
    .payment-container { background: #000; padding: 20px; display: flex; flex-direction: column; }
    .form-title { font-size: 18px; font-weight: 600; margin: 0 0 20px; color: #fff; }
    .field-container { margin-bottom: 16px; }
    .field-label { display: block; margin-bottom: 8px; font-size: 14px; font-weight: 500; color: #fff; }
    #cardholder-name {
      border: 1px solid #333; border-radius: 12px; padding: 8px 16px;
      background: #1a1a1a; height: 36px; width: 100%;
      font-size: 16px; color: #fff;
      transition: border-color .2s, background-color .2s;
    }
    #cardholder-name:focus { border-color: #fff; background: #252525; outline: none; }
    #cardholder-name::placeholder { color: #888; }
    #card-container, #expiry-container, #cvv-container {
      border: 1px solid #333; border-radius: 12px; padding: 8px 16px;
      background: #1a1a1a; height: 36px; width: 100%; font-size: 16px;
      transition: border-color .2s, background-color .2s;
      display: flex; align-items: center;
    }
    #card-container:focus-within, #expiry-container:focus-within, #cvv-container:focus-within {
      border-color: #fff; background: #252525;
    }
    .input-invalid { border-color: #ff4444 !important; }
    .row-fields { display: flex; gap: 12px; margin-bottom: 16px; }
    .row-fields .field-container { flex: 1; margin-bottom: 0; }
    .field-error { color: #ff4444; font-size: 12px; margin-top: 4px; display: none; }
    .field-error.show { display: block; }
    .submit-button {
      position: relative; width: 100%; padding: 16px;
      background-color: #fff; color: #000; border: none; border-radius: 12px;
      font-size: 16px; font-weight: 600; cursor: pointer; margin-top: 8px;
      transition: opacity .2s; display: flex; align-items: center; justify-content: center;
    }
    .submit-button:active { opacity: .8; }
    .submit-button:disabled { background-color: #333; color: #666; cursor: not-allowed; opacity: 1; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .button-spinner {
      display: inline-block; width: 20px; height: 20px;
      border: 3px solid rgba(0,0,0,.12); border-top-color: #000;
      border-radius: 50%; animation: spin .8s linear infinite;
    }
    #button-loading {
      display: none; position: absolute; left: 0; right: 0; top: 0; bottom: 0;
      align-items: center; justify-content: center;
    }
    .submit-button.loading #button-text { visibility: hidden; }
    .submit-button.loading #button-loading { display: flex !important; align-items: center; justify-content: center; }
  </style>
  <script src="${checkoutHost}/sdk/hosted-fields.js"></script>
</head>
<body>
  <div class="payment-container">
    <h2 class="form-title">Enter Card Details</h2>
    <div class="field-container" id="cardholder-name-container">
      <label class="field-label">Cardholder Name</label>
      <input type="text" id="cardholder-name" placeholder="Cardholder name"
             autocomplete="cc-name" inputmode="text" autocapitalize="words"
             pattern="[A-Za-z\s\-'.]*" value="$nameEscaped" />
      <div class="field-error" id="cardholder-name-error"></div>
    </div>
    <div class="field-container" id="card-number-container">
      <label class="field-label">Card Number</label>
      <div id="card-container"></div>
      <div class="field-error" id="card-number-error"></div>
    </div>
    <div class="row-fields">
      <div class="field-container" id="expiry-container-wrapper">
        <label class="field-label">Expiration Date</label>
        <div id="expiry-container"></div>
        <div class="field-error" id="expiry-error"></div>
      </div>
      <div class="field-container" id="cvv-container-wrapper">
        <label class="field-label">CVV</label>
        <div id="cvv-container"></div>
        <div class="field-error" id="cvv-error"></div>
      </div>
    </div>
    <button id="submit-button" class="submit-button" disabled>
      <span id="button-text">Pay</span>
      <span id="button-loading"><span class="button-spinner"></span></span>
    </button>
  </div>
  <script>
    var token = "$tokenEscaped";
    var config = {
      sessionToken: token,
      fields: {
        card:   { id: "card-container",   placeholder: "Card number" },
        expiry: { id: "expiry-container", placeholder: "MM/YY" },
        cvv:    { id: "cvv-container",    placeholder: "CVV" }
      },
      style: {
        input: {
          "font-size": "16px",
          "font-family": "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
          "color": "#ffffff", "background": "transparent", "border": "none",
          "outline": "none", "width": "100%"
        },
        "::placeholder": { "font-size": "16px", "color": "#888888", "opacity": "1" }
      }
    };
    var hostedFields, onSubmitResultCallback;

    function getHpfFieldValue(id) {
      try {
        var iframe = document.querySelector("#" + id + " iframe");
        if (!iframe || !iframe.contentDocument) return '';
        var input = iframe.contentDocument.querySelector("input");
        return input ? (input.value || '').trim() : '';
      } catch (e) { return ''; }
    }

    function updatePayButtonState() {
      var ch = document.getElementById('cardholder-name');
      var empty = !ch || !ch.value || ch.value.trim() === '';
      var allFilled = !empty
        && getHpfFieldValue("card-container") !== ''
        && getHpfFieldValue("expiry-container") !== ''
        && getHpfFieldValue("cvv-container") !== '';
      var btn = document.getElementById('submit-button');
      if (btn) btn.disabled = !allFilled;
    }

    function applyNumericKeyboard(id) {
      try {
        var iframe = document.querySelector("#" + id + " iframe");
        if (!iframe || !iframe.contentDocument) return;
        var input = iframe.contentDocument.querySelector("input");
        if (!input) return;
        input.setAttribute("inputmode", "numeric");
        input.setAttribute("pattern", "[0-9 ]*");
        input.setAttribute("type", "tel");
      } catch (e) {}
    }

    function initializeHPF() {
      try {
        hostedFields = new HostedFields();
        hostedFields.init(config);

        function wireField(on, containerId) {
          if (!on) return;
          on.input = function(d) {
            var c = document.querySelector("#" + containerId);
            if (c) { d.valid ? c.classList.remove("input-invalid") : c.classList.add("input-invalid"); }
            updatePayButtonState();
          };
          on.focus = function() {
            var c = document.querySelector("#" + containerId);
            if (c) { c.style.borderColor = "#fff"; c.style.background = "#252525"; }
            applyNumericKeyboard(containerId);
          };
          on.blur = function() {
            var c = document.querySelector("#" + containerId);
            if (c) { c.style.borderColor = "#333"; c.style.background = "#1a1a1a"; }
          };
        }
        wireField(hostedFields.cardOn,   "card-container");
        wireField(hostedFields.expiryOn, "expiry-container");
        wireField(hostedFields.cvvOn,    "cvv-container");

        var ch = document.getElementById('cardholder-name');
        if (ch) {
          ch.setAttribute('inputmode', 'text');
          ch.addEventListener('input', function() {
            var v = ch.value, f = v.replace(/[0-9]/g, '');
            if (v !== f) {
              var s = ch.selectionStart;
              ch.value = f;
              ch.setSelectionRange(Math.max(0, s - (v.length - f.length)),
                                  Math.max(0, s - (v.length - f.length)));
            }
            updatePayButtonState();
          });
        }

        updatePayButtonState();
        setInterval(updatePayButtonState, 400);

        onSubmitResultCallback = function(data) {
          var nameInput = document.getElementById('cardholder-name');
          if (nameInput && nameInput.value) data.cardholderName = nameInput.value.trim();
          if (!data.cardholderName || data.cardholderName.trim() === '') {
            var btn = document.getElementById('submit-button');
            if (btn) { btn.classList.remove('loading'); updatePayButtonState(); }
            HPFChannel.postMessage(JSON.stringify({ type: 'onError', error: 'Cardholder name is required.' }));
            return;
          }
          if (!data.message || data.message.trim() === '') {
            if (data.result === 'success') data.message = 'Card data collected successfully';
          }
          HPFChannel.postMessage(JSON.stringify({ type: 'onSubmitResult', data: data }));
        };
        hostedFields.onSubmitResult = onSubmitResultCallback;

        var submitBtn = document.getElementById('submit-button');
        if (submitBtn) {
          submitBtn.addEventListener('click', function(e) {
            e.preventDefault();
            var nameInput = document.getElementById('cardholder-name');
            var name = nameInput ? nameInput.value.trim() : '';
            if (!name) {
              HPFChannel.postMessage(JSON.stringify({ type: 'onError', error: 'Cardholder name is required.' }));
              return;
            }
            if (getHpfFieldValue("card-container") === '' || getHpfFieldValue("expiry-container") === '' || getHpfFieldValue("cvv-container") === '') {
              HPFChannel.postMessage(JSON.stringify({ type: 'onError', error: 'Please fill in all card details.' }));
              return;
            }
            submitBtn.disabled = true;
            submitBtn.classList.add('loading');
            if (hostedFields && typeof hostedFields.collectIframesData === 'function') {
              hostedFields.collectIframesData();
            } else {
              submitBtn.disabled = false;
              submitBtn.classList.remove('loading');
              HPFChannel.postMessage(JSON.stringify({ type: 'onError', error: 'Payment system error. Please try again.' }));
            }
          });
        }

        window.resetPaymentButton = function() {
          var btn = document.getElementById('submit-button');
          if (btn) { btn.classList.remove('loading'); updatePayButtonState(); }
        };
      } catch (err) {
        HPFChannel.postMessage(JSON.stringify({ type: 'onError', error: err.toString() }));
      }
    }

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', initializeHPF);
    } else {
      initializeHPF();
    }
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun buildPostRedirectForm(
        redirectUrl: String,
        redirectParams: List<Map<String, Any?>>,
    ): String {
        val fields = buildString {
            for (paramMap in redirectParams) {
                for ((key, value) in paramMap) {
                    val ek = key.replace("\"", "&quot;").replace("'", "&#39;")
                    val ev = value.toString().replace("\"", "&quot;").replace("'", "&#39;")
                    appendLine("  <input type=\"hidden\" name=\"$ek\" value=\"$ev\" />")
                }
            }
        }
        val escapedUrl = redirectUrl.replace("\"", "&quot;").replace("'", "&#39;")
        return """
<!DOCTYPE html>
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Redirecting...</title></head>
<body style="background:#000;color:#fff;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
  <div style="text-align:center;">
    <div style="width:40px;height:40px;border:3px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite;margin:0 auto 20px;"></div>
    <p style="margin:0;font-size:16px;">Redirecting for authentication...</p>
  </div>
  <form id="redirectForm" method="POST" action="$escapedUrl" style="display:none;">
$fields
  </form>
  <style>@keyframes spin { to { transform: rotate(360deg); } }</style>
  <script>document.getElementById('redirectForm').submit();</script>
</body>
</html>
        """.trimIndent()
    }
}
