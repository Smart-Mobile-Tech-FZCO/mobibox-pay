package io.mobibox.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches session tokens from the Mobibox API.
 *
 * Endpoint: `POST {checkoutHost}/api/v1/session/token`
 *
 * Reference: https://docs.mobibox.io/docs/guides/hosted_payment_fields
 */
internal class MobiPaySessionToken(
    private val checkoutHost: String,
    private val merchantKey: String,
    private val password: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    /**
     * Obtain a session token for Hosted Payment Fields.
     *
     * @return The session token string.
     * @throws MobiPayException on API or network errors.
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
    ): String = withContext(Dispatchers.IO) {

        val hash = MobiPayHash.generateAuthHash(
            orderNumber = orderNumber,
            orderAmount = orderAmount,
            orderCurrency = orderCurrency,
            orderDescription = orderDescription,
            password = password,
        )

        val order = JSONObject().apply {
            put("number", orderNumber)
            put("amount", orderAmount)
            put("currency", orderCurrency)
            put("description", orderDescription)
        }

        val body = JSONObject().apply {
            put("operation", "purchase")
            put("merchant_key", merchantKey)
            put("order", order)
            put("hash", hash)
            put("return_url", RETURN_URL)
            recurringInit?.let { put("recurring_init", it) }
            scheduleId?.let { put("schedule_id", it) }
            reqToken?.let { put("req_token", it) }
            cardToken?.takeIf { it.isNotEmpty() }?.let { put("card_token", JSONArray(it)) }
        }

        MobiPayLogger.d("── Session Token Request ──")
        MobiPayLogger.d("  URL: $checkoutHost/api/v1/session/token")
        MobiPayLogger.d("  Body: $body")

        val request = Request.Builder()
            .url("$checkoutHost/api/v1/session/token")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw MobiPayException("Empty response from session token API")

        MobiPayLogger.d("── Session Token Response ──")
        MobiPayLogger.d("  HTTP ${response.code}")
        MobiPayLogger.d("  Body: $responseBody")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).optString("error_message", "Failed to get session token")
            } catch (_: Exception) {
                "Failed to get session token (HTTP ${response.code})"
            }
            MobiPayLogger.e("  Session token FAILED: $errorMsg")
            throw MobiPayException(errorMsg)
        }

        val json = JSONObject(responseBody)
        val token = json.optString("token", "").takeIf { it.isNotEmpty() }
            ?: throw MobiPayException("Token not found in response")
        MobiPayLogger.d("  Session token OK: ${token.take(20)}...")
        token
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val RETURN_URL = "https://sdk.mobibox.io/payment_processing.html"
    }
}
