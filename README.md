# MobiPay Android SDK (Kotlin)

**Native Android SDK for Mobibox Payment Orchestrator**

`mobibox-pay` is a native Android library that lets you integrate **Mobibox Payment Orchestrator** into any Android app. Through a single unified interface you get access to hundreds of payment connectors — making payment integration faster and more reliable.

---

## Features

- One-line payment flow via `MobiPay.startPayment()`
- Hosted Payment Fields (HPF) — PCI-compliant card collection via WebView
- Automatic 3D Secure (3DS) handling with redirect + status polling
- Recurring payments, card tokenization, and returning-customer tokens
- Dark-themed BottomSheet UI (matches the Flutter SDK's look)
- Callback-driven architecture — `onTransactionComplete`, `onTransactionFailed`, etc.
- Pure Kotlin, no annotation processors, minSdk 21

---

## Requirements

| Requirement   | Minimum       |
|---------------|---------------|
| Android SDK   | API 21+       |
| Kotlin        | 1.9+          |
| Gradle        | 8.x           |
| AGP           | 8.2+          |

---

## Installation

### Option 1 — Gradle dependency (JitPack)

**Step 1.** Add JitPack to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Or if you're using `build.gradle` (Groovy):

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Step 2.** Add the dependency in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.mobibox:mobibox-pay:1.1.0")
}
```

Or in Groovy `build.gradle`:

```groovy
dependencies {
    implementation 'io.mobibox:mobibox-pay:1.1.0'
}
```

**Step 3.** Sync Gradle and you're ready to go.

[![](https://jitpack.io/v/smart-mobile-tech-apps/mobibox-pay.svg)](https://jitpack.io/#smart-mobile-tech-apps/mobibox-pay)

### Option 2 — Local module

Copy the `mobibox-pay` folder into your project and include it in `settings.gradle.kts`:

```kotlin
include(":mobibox-pay")
```

Then depend on it:

```kotlin
dependencies {
    implementation(project(":mobibox-pay"))
}
```

---

## Quick Start

### 1. Initialize

```kotlin
import io.mobibox.pay.MobiPay

class MyActivity : AppCompatActivity() {

    private val mobiPay = MobiPay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable debug logging (optional — remove for production)
        MobiPay.setDebugMode(true)

        mobiPay.initialize(
            checkoutHost = "https://pay.example.com",
            merchantKey  = "your-merchant-key",
            password     = "your-merchant-password",
        )
    }
}
```

### 2. Start a Payment

```kotlin
mobiPay.startPayment(
    activity         = this,
    orderNumber      = "order-${System.currentTimeMillis()}",
    orderAmount      = "10.00",
    orderCurrency    = "USD",
    orderDescription = "Premium subscription",
    cardholderName   = "JOHN DOE",       // optional — pre-fills the name field
    email            = "john@example.com", // optional
    onTransactionComplete = { result ->
        Log.d("Pay", "Success! Payment ID: ${result.paymentId}")
    },
    onTransactionFailed = { result ->
        Log.d("Pay", "Failed: ${result.declineMessage}")
    },
    onError = { exception ->
        Log.e("Pay", "Error: ${exception.message}")
    },
    onPaywallClosed = {
        Log.d("Pay", "User closed the payment sheet")
    },
)
```

That's it. The SDK handles session-token creation, card collection, 3DS redirects, status polling, and success/error screens automatically.

---

## API Reference

### `MobiPay`

| Method | Description |
|---|---|
| `initialize(checkoutHost, merchantKey, password)` | Configure credentials. Must be called once before any payment. |
| `startPayment(activity, ..., callbacks)` | Opens a BottomSheet and runs the full payment flow. |
| `suspend getSessionToken(...)` | Returns a session token (advanced — for custom UI). |
| `setDebugMode(enabled)` | **Static.** Enable/disable debug logging (off by default). |

### `startPayment` Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `activity` | `FragmentActivity` | Yes | The hosting activity. |
| `orderNumber` | `String` | Yes | Unique order identifier. |
| `orderAmount` | `String` | Yes | Amount (e.g. `"10.00"`). |
| `orderCurrency` | `String` | Yes | ISO 4217 code (e.g. `"USD"`). |
| `orderDescription` | `String` | Yes | Human-readable description. |
| `cardholderName` | `String?` | No | Pre-fill cardholder name field. |
| `email` | `String?` | No | Customer email. |
| `billingAddress` | `Map?` | No | Billing address fields. |
| `browserInfo` | `Map?` | No | Browser information. |
| `birthDay` | `String?` | No | `YYYY-MM-DD` format. |
| `selectedLanguage` | `String?` | No | Language code. |
| `recurringInit` | `Boolean?` | No | Initialize a recurring payment. |
| `scheduleId` | `String?` | No | Schedule ID for recurring. |
| `reqToken` | `Boolean?` | No | Request a card token. |
| `cardToken` | `List<String>?` | No | Tokens for returning customers. |

### Callbacks

| Callback | Type | Description |
|---|---|---|
| `onSubmitResult` | `(Map<String, Any?>) -> Unit` | Card form submitted (HPF data collected). |
| `onTransactionInitiated` | `(MobiPayTransactionResult) -> Unit` | Transaction initiated — fires for ALL results. |
| `onTransactionComplete` | `(MobiPayTransactionResult) -> Unit` | Transaction successful (settled). |
| `onTransactionFailed` | `(MobiPayTransactionResult) -> Unit` | Transaction failed (decline, void, etc.). |
| `onSuccessRedirect` | `(String) -> Unit` | Redirect to success URL. |
| `onErrorRedirect` | `(String) -> Unit` | Redirect to error URL. |
| `onError` | `(Exception) -> Unit` | Any exception during the flow. |
| `onPaywallClosed` | `() -> Unit` | The payment BottomSheet was dismissed. |

### `MobiPayTransactionResult`

| Property | Type | Description |
|---|---|---|
| `status` | `String` | Transaction status. |
| `publicId` | `String?` | Unique payment identifier. |
| `paymentId` | `String?` | Payment ID for status checks. |
| `transactionId` | `String?` | Transaction ID. |
| `orderId` | `String?` | Order ID. |
| `hash` | `String?` | Verification hash. |
| `declineMessage` | `String?` | Decline / error message. |
| `redirectUrl` | `String?` | Redirect URL (if applicable). |
| `redirectParams` | `Map?` | Redirect params (POST redirect). |
| `rawData` | `Map?` | Complete raw API response. |
| `isSuccess` | `Boolean` | `true` if settled or success. |
| `isDeclined` | `Boolean` | `true` if declined. |
| `requiresAction` | `Boolean` | `true` if redirect/waiting. |

---

## Payment Flow

```
┌──────────────────────────────────────────────────┐
│  1. mobiPay.startPayment(...)                    │
│     ↓                                            │
│  2. SDK fetches session token                    │
│     POST /api/v1/session/token                   │
│     ↓                                            │
│  3. BottomSheet opens with card form (HPF)       │
│     ↓                                            │
│  4. User fills card details → taps Pay           │
│     ↓                                            │
│  5. SDK processes payment                        │
│     POST /api/v1/processing/purchase/card        │
│     ↓                                            │
│  6a. success → onTransactionComplete → thank you │
│  6b. decline → onTransactionFailed → error       │
│  6c. redirect → 3DS WebView → status polling     │
│       POST /api/v1/payment/status (every 5s)     │
│       → settled  → onTransactionComplete         │
│       → decline  → onTransactionFailed           │
│  6d. waiting → status polling (same as above)    │
└──────────────────────────────────────────────────┘
```

---

## Transaction Statuses

### From Purchase API

| Status | Description |
|---|---|
| `success` | Transaction completed successfully. |
| `decline` | Transaction declined. |
| `redirect` | 3DS or other redirect required. |
| `waiting` | Transaction is being processed. |
| `undefined` | Uncertain status. |

### From GET_TRANS_STATUS

| Status | Description |
|---|---|
| `prepare` | Payment being prepared. |
| `settled` | Payment completed. |
| `pending` | Payment pending. |
| `3ds` | 3DS authentication in progress. |
| `redirect` | Redirect required. |
| `decline` | Payment declined. |
| `refund` | Payment refunded. |
| `reversal` | Payment reversed. |
| `void` | Payment voided. |
| `chargeback` | Chargeback received. |

---

## Recurring Payments

```kotlin
mobiPay.startPayment(
    activity         = this,
    orderNumber      = "sub-001",
    orderAmount      = "9.99",
    orderCurrency    = "USD",
    orderDescription = "Monthly subscription",
    recurringInit    = true,
    scheduleId       = "sched-abc-123",
    reqToken         = true,
    onTransactionComplete = { result ->
        // Store result.paymentId for future recurring charges
    },
)
```

For returning customers with saved tokens:

```kotlin
mobiPay.startPayment(
    // ...
    cardToken = listOf("saved_token_abc", "saved_token_def"),
    // ...
)
```

---

## Advanced: Custom UI with Session Tokens

If you want full control over the UI, use `getSessionToken()` directly:

```kotlin
lifecycleScope.launch {
    try {
        val token = mobiPay.getSessionToken(
            orderNumber      = "order-123",
            orderAmount      = "25.00",
            orderCurrency    = "USD",
            orderDescription = "Custom checkout",
        )
        // Use `token` with your own WebView / HPF integration
    } catch (e: MobiPayException) {
        Log.e("Pay", "Token error: ${e.message}")
    }
}
```

---

## Debug Logging

The SDK includes detailed logging for API requests/responses, payment flow steps, WebView navigation, and status polling. **Logging is disabled by default** and produces zero output in production.

To enable during development:

```kotlin
MobiPay.setDebugMode(true)
```

Then filter Logcat by tag **`MobiPay`** to see the full flow:

```
MobiPay  ══════════════════════════════════════
MobiPay    MobiPay SDK initializing
MobiPay    Host: https://pay.example.com
MobiPay  ══════════════════════════════════════
MobiPay  ── Session Token Request ──
MobiPay    URL: https://pay.example.com/api/v1/session/token
MobiPay  ── Session Token Response ──
MobiPay    HTTP 200
MobiPay  ── JS → Kotlin ──
MobiPay    Type: onSubmitResult
MobiPay  ── Purchase Request ──
MobiPay  ── Purchase Response ──
MobiPay    Purchase result: redirect
MobiPay  ── Starting Status Polling ──
MobiPay    Poll #1 / 60 → 3ds
MobiPay    Poll #2 / 60 → settled
```

**Important:** Always disable debug mode before releasing:

```kotlin
// MobiPay.setDebugMode(true)  // ← comment out or remove for production
```

---

## ProGuard / R8

The SDK ships with consumer ProGuard rules. No additional configuration is needed.

---

## Project Structure

```
mobi_pay_kotlin/
├── mobibox-pay/                          # SDK library module
│   └── src/main/java/io/mobibox/pay/
│       ├── MobiPay.kt                    # Main entry point
│       ├── MobiPayCheckoutSheet.kt       # BottomSheet payment flow
│       ├── MobiPayHPFWebView.kt          # WebView + HPF + 3DS
│       ├── MobiPayPaymentProcessor.kt    # Purchase + status APIs
│       ├── MobiPaySessionToken.kt        # Session token API
│       ├── MobiPayHash.kt               # SHA1(MD5(...)) signing
│       ├── MobiPayTransactionResult.kt   # Result data class
│       ├── MobiPayException.kt           # SDK exception
│       └── MobiPayLogger.kt             # Debug-only logger
├── app/                                  # Example app
│   └── src/main/java/.../MainActivity.kt
├── build.gradle.kts                      # Root build
├── settings.gradle.kts
└── README.md                             # This file
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `okhttp3` | 4.12.0 | HTTP networking |
| `kotlinx-coroutines-android` | 1.7.3 | Async / coroutines |
| `material` | 1.11.0 | BottomSheetDialogFragment |
| `appcompat` | 1.6.1 | AndroidX compatibility |
| `lifecycle-runtime-ktx` | 2.7.0 | Lifecycle-aware coroutines |

No annotation processors, no Retrofit, no Gson/Moshi — keeps the SDK lightweight.

---

## License

Proprietary — Mobibox Technologies. All rights reserved.
