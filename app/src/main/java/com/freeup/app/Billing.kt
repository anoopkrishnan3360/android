package com.freeup.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow

/** One-time "premium_lifetime" in-app product. Create it in Play Console > Monetize > In-app products. */
class Billing(private val ctx: Context) : PurchasesUpdatedListener {
    companion object { const val PRODUCT_ID = "premium_lifetime" }
    private val prefs = ctx.getSharedPreferences("billing", Context.MODE_PRIVATE)
    val premium = MutableStateFlow(prefs.getBoolean("premium", false))
    val price = MutableStateFlow<String?>(null)
    private var details: ProductDetails? = null
    private val client = BillingClient.newBuilder(ctx).setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) { loadDetails(); restore() }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadDetails() {
        val p = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP).build())).build()
        client.queryProductDetailsAsync(p) { _, list ->
            details = list.firstOrNull()
            price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** Also serves as "Restore purchases". Revokes premium if the purchase no longer exists (e.g. refund). */
    fun restore() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                if (!handle(list)) setPremium(false)
            }
        }
    }

    fun buy(activity: Activity) {
        val d = details ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())).build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        if (r.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases)
    }

    private fun handle(purchases: List<Purchase>): Boolean {
        var found = false
        for (p in purchases) {
            if (PRODUCT_ID in p.products && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                found = true
                if (!p.isAcknowledged) client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
                setPremium(true)
            }
        }
        return found
    }

    private fun setPremium(v: Boolean) { prefs.edit().putBoolean("premium", v).apply(); premium.value = v }
}
