package io.mobibox.pay

import java.security.MessageDigest

/**
 * Utility for generating Mobibox hash signatures.
 *
 * Formula: SHA1(MD5(UPPERCASE(concatenated_string)))
 * where MD5 is first converted to a hex string, then SHA1 is applied to that hex string.
 */
internal object MobiPayHash {

    /**
     * Generate authentication hash for checkout / session token requests.
     *
     * `SHA1(MD5(UPPERCASE(orderNumber + orderAmount + orderCurrency + orderDescription + password)))`
     */
    fun generateAuthHash(
        orderNumber: String,
        orderAmount: String,
        orderCurrency: String,
        orderDescription: String,
        password: String,
    ): String {
        val concatenated = "$orderNumber$orderAmount$orderCurrency$orderDescription$password"
        return sha1OfMd5(concatenated.uppercase())
    }

    /**
     * Generate hash for GET_TRANS_STATUS by payment_id.
     *
     * `SHA1(MD5(UPPERCASE(paymentId + password)))`
     */
    fun generateTransactionStatusHash(
        paymentId: String,
        password: String,
    ): String {
        val concatenated = "$paymentId$password"
        return sha1OfMd5(concatenated.uppercase())
    }

    private fun sha1OfMd5(input: String): String {
        val md5Bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val md5Hex = md5Bytes.toHexString()
        val sha1Bytes = MessageDigest.getInstance("SHA-1").digest(md5Hex.toByteArray(Charsets.UTF_8))
        return sha1Bytes.toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
