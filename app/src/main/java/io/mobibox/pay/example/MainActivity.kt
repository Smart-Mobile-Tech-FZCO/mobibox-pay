package io.mobibox.pay.example

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import io.mobibox.pay.MobiPay

class MainActivity : AppCompatActivity() {

    private val mobiPay = MobiPay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mobiPay.initialize(
            checkoutHost = "xxxxx-xxxxxx-xxxxxx",
            merchantKey = "xxxxx-xxxxxx-xxxxxx",
            password = "xxxxx-xxxxxx-xxxxxx",
        )

        findViewById<Button>(R.id.btn_start_payment).setOnClickListener {
            startPayment()
        }
    }

    private fun startPayment() {
        mobiPay.startPayment(
            activity = this,
            orderNumber = "order-${System.currentTimeMillis()}",
            orderAmount = "1.00",
            orderCurrency = "USD",
            orderDescription = "Test Purchase",
            cardholderName = "JOHN DOE",
            email = "test@example.com",
            // Optional: Recurring payment parameters
            // recurringInit = true,
            // scheduleId = "xxxxx-xxxx-xxxx-xxx",
            // reqToken = true,
            // cardToken = listOf("token_abc", "token_def"),
            onSubmitResult = { data ->
                Log.d(TAG, "Payment submitted: $data")
            },
            onTransactionInitiated = { result ->
                Log.d(TAG, "Transaction initiated:")
                Log.d(TAG, "  Status: ${result.status}")
                Log.d(TAG, "  Payment ID: ${result.paymentId}")
                Log.d(TAG, "  Transaction ID: ${result.transactionId}")
                Log.d(TAG, "  Order ID: ${result.orderId}")
                Log.d(TAG, "  Public ID: ${result.publicId}")
            },
            onTransactionComplete = { result ->
                Log.d(TAG, "Transaction completed:")
                Log.d(TAG, "  Status: ${result.status}")
                Log.d(TAG, "  Payment ID: ${result.paymentId}")
                Log.d(TAG, "  Transaction ID: ${result.transactionId}")
                Log.d(TAG, "  Order ID: ${result.orderId}")
                Log.d(TAG, "  Public ID: ${result.publicId}")
                Log.d(TAG, "  Is Success: ${result.isSuccess}")
                Log.d(TAG, "  Full result: ${result.toMap()}")
            },
            onTransactionFailed = { result ->
                Log.d(TAG, "Transaction failed:")
                Log.d(TAG, "  Status: ${result.status}")
                Log.d(TAG, "  Payment ID: ${result.paymentId}")
                Log.d(TAG, "  Decline Message: ${result.declineMessage}")
                Log.d(TAG, "  Full result: ${result.toMap()}")
            },
            onSuccessRedirect = { url ->
                Log.d(TAG, "Success redirect: $url")
            },
            onErrorRedirect = { url ->
                Log.d(TAG, "Error redirect: $url")
            },
            onError = { e ->
                Log.e(TAG, "Exception: ${e.message}", e)
            },
            onPaywallClosed = {
                Log.d(TAG, "Paywall closed by user")
            },
        )
    }

    companion object {
        private const val TAG = "MobiPayExample"
    }
}
