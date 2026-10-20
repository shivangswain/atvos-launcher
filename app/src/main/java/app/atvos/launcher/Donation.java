package app.atvos.launcher;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.List;

/**
 * Settings > Support > Donate, through Google Play Billing. {@link #PRODUCT} is a consumable in-app product. It is
 * consumed immediately after the purchase, and it unlocks nothing.
 *
 * The connection to Google Play starts when the Support page opens ({@link #start}) and ends when Settings closes
 * ({@link #end}). All calls are on the UI thread. Callbacks from Google Play go to the UI thread.
 */
final class Donation {

    /** The id of the in-app product in Play Console. */
    static final String PRODUCT = "donation";

    /** The state that the Donate row shows. */
    enum State { CONNECTING, READY, UNAVAILABLE, PENDING, THANKS }

    interface Listener {
        void changed();
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BillingClient client;
    private ProductDetails product;
    private Listener listener;
    private State state = State.UNAVAILABLE;

    Donation(Activity activity) {
        this.activity = activity;
    }

    State state() {
        return state;
    }

    /** Returns the local price from Google Play (for example, "₹99"), or null before it is known. */
    String price() {
        ProductDetails.OneTimePurchaseOfferDetails offer = product != null ? product.getOneTimePurchaseOfferDetails() : null;
        return offer != null ? offer.getFormattedPrice() : null;
    }

    /** Connects to Google Play and gets the price of the donation. {@code listener} receives each change of state. */
    void start(Listener listener) {
        this.listener = listener;
        if (client != null) return;
        set(State.CONNECTING);
        try {
            client = BillingClient.newBuilder(activity)
                    .setListener((result, purchases) -> main.post(() -> purchased(result, purchases)))
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build();
            BillingClient connecting = client;
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult result) {
                    main.post(() -> {
                        if (client != connecting) return; // end() was called before the connection
                        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            set(State.UNAVAILABLE);
                            return;
                        }
                        lookUp();
                        finishEarlier();
                    });
                }

                @Override
                public void onBillingServiceDisconnected() {
                    main.post(() -> {
                        if (client != connecting) return;
                        client = null; // connects again when Settings opens again
                        product = null;
                        if (state != State.THANKS) set(State.UNAVAILABLE);
                    });
                }
            });
        } catch (RuntimeException e) {
            client = null;
            set(State.UNAVAILABLE); // Google Play is not available
        }
    }

    /** Ends the connection to Google Play. */
    void end() {
        listener = null;
        if (client != null) client.endConnection();
        client = null;
        product = null;
        if (state != State.THANKS) state = State.UNAVAILABLE;
    }

    /**
     * Opens the purchase sheet of Google Play for the donation. It connects again first if the connection is closed.
     */
    void give() {
        if (client == null && listener != null) {
            start(listener);
            return;
        }
        if (client == null || product == null) return;
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(List.of(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product).build()))
                .build();
        client.launchBillingFlow(activity, params);
    }

    private void lookUp() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(List.of(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()))
                .build();
        BillingClient asked = client;
        client.queryProductDetailsAsync(params, (result, found) -> main.post(() -> {
            if (client != asked) return;
            List<ProductDetails> list = found != null ? found.getProductDetailsList() : null;
            product = result.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()
                    ? list.get(0) : null;
            // A pending purchase or a completed donation replaces the price in the row.
            if (state == State.PENDING || state == State.THANKS) {
                if (listener != null) listener.changed();
            } else {
                set(product != null && price() != null ? State.READY : State.UNAVAILABLE);
            }
        }));
    }

    /** Consumes a donation that was paid before but not consumed. */
    private void finishEarlier() {
        BillingClient asked = client;
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> main.post(() -> {
                    if (client == asked && result.getResponseCode() == BillingClient.BillingResponseCode.OK) consumeAll(purchases);
                }));
    }

    private void purchased(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) consumeAll(purchases);
        // Other result codes: Google Play shows the error.
    }

    private void consumeAll(List<Purchase> purchases) {
        if (purchases == null || client == null) return;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                set(State.PENDING); // Google Play completes it later. It is consumed at the next start.
                continue;
            }
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                    (done, token) -> main.post(() -> {
                        if (done.getResponseCode() == BillingClient.BillingResponseCode.OK) set(State.THANKS);
                    }));
        }
    }

    private void set(State s) {
        state = s;
        if (listener != null) listener.changed();
    }
}
