package io.mobibox.pay

/**
 * Transaction result returned after payment processing.
 *
 * Possible [status] values from the purchase API:
 * `success`, `decline`, `redirect`, `waiting`, `undefined`
 *
 * Possible [status] values from GET_TRANS_STATUS:
 * `prepare`, `settled`, `pending`, `3ds`, `redirect`, `decline`,
 * `refund`, `reversal`, `void`, `chargeback`
 */
data class MobiPayTransactionResult(
    /** Transaction status. */
    val status: String,
    /** Unique payment identifier (public_id from API). */
    val publicId: String? = null,
    /** Payment ID used for status polling. */
    val paymentId: String? = null,
    /** Transaction ID. */
    val transactionId: String? = null,
    /** Order ID. */
    val orderId: String? = null,
    /** Verification hash. */
    val hash: String? = null,
    /** Decline / error message. */
    val declineMessage: String? = null,
    /** Redirect URL (if status = redirect). */
    val redirectUrl: String? = null,
    /** Redirect parameters map. If present, redirect must use POST. */
    val redirectParams: Map<String, Any?>? = null,
    /** Complete raw response data. */
    val rawData: Map<String, Any?>? = null,
) {
    /** True when status indicates success (success or settled). */
    val isSuccess: Boolean
        get() = status == "success" || status == "settled"

    /** True when status is decline. */
    val isDeclined: Boolean
        get() = status == "decline"

    /** True when the transaction requires additional action (redirect / waiting). */
    val requiresAction: Boolean
        get() = status == "redirect" || status == "waiting"

    fun toMap(): Map<String, Any?> = mapOf(
        "status" to status,
        "publicId" to publicId,
        "paymentId" to paymentId,
        "transactionId" to transactionId,
        "orderId" to orderId,
        "hash" to hash,
        "declineMessage" to declineMessage,
        "redirectUrl" to redirectUrl,
        "redirectParams" to redirectParams,
        "isSuccess" to isSuccess,
        "isDeclined" to isDeclined,
        "requiresAction" to requiresAction,
    )
}
