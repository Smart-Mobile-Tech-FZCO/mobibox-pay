package io.mobibox.pay

import android.util.Log

/**
 * Internal logger for the MobiPay SDK.
 *
 * Disabled by default. Enable via [MobiPay.setDebugMode].
 */
internal object MobiPayLogger {

    private const val TAG = "MobiPay"

    @Volatile
    var isEnabled: Boolean = false

    fun d(message: String) {
        if (isEnabled) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (isEnabled) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) Log.e(TAG, message, throwable)
            else Log.e(TAG, message)
        }
    }
}
