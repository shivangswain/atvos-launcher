package app.atvos.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
 * ({@link #end}). A donation that isn't complete yet (a slow payment method) is checked again at each start of atvOS
 * ({@link #settle}), because Google Play refunds a purchase that isn't consumed within three days. All calls are on the
 * UI thread. Callbacks from Google Play go to the UI thread.
 */
final class Donation {

    /** The id of the in-app product in Play Console. */
    static final String PRODUCT = "donation";

    /** A purchase sheet opened, or Google Play has a donation that isn't complete: {@link #settle} checks it. */
    private static final String KEY_OPEN = "donation_open";

    /** The state that the Donate row shows. */
    enum State { CONNECTING, READY, UNAVAILABLE, PENDING, THANKS }

    interface Listener {
        void changed();
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BillingClient client;
    private ProductDetails product;
    private Listener listener;
    private State state = State.UNAVAILABLE;

    private boolean settling; // connected for settle() only: the connection ends when the check is done
    private int consuming;    // consumes that haven't returned

    Donation(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Consumes a donation that Google Play completed after Settings closed, before Google Play refunds it. It connects
     * only after a purchase sheet opened, until Google Play has no open donation. UI thread.
     */
    void settle() {
        if (client != null || !prefs.getBoolean(KEY_OPEN, false)) return;
        settling = true;
        start(null);
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
        if (listener != null) settling = false; // Settings uses the connection now
        if (client != null) {
            // A connection that settle() made has no price yet. A connection that isn't ready looks it up when ready.
            if (listener != null && product == null && client.isReady()) lookUp();
            return;
        }
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
                            if (settling) end();
                            return;
                        }
                        if (!settling) lookUp();
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
        } catch (RuntimeException | LinkageError e) {
            // Google Play is not available. LinkageError: a class that the library needs is not in the app.
            client = null;
            set(State.UNAVAILABLE);
        }
    }

    /** Ends the connection to Google Play. */
    void end() {
        listener = null;
        settling = false;
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
        try {
            BillingFlowParams params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(List.of(BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product).build()))
                    .build();
            prefs.edit().putBoolean(KEY_OPEN, true).apply(); // the app can close before Google Play answers
            client.launchBillingFlow(activity, params);
        } catch (RuntimeException | LinkageError e) {
            set(State.UNAVAILABLE);
        }
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
                    if (client != asked) return;
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        consumeAll(purchases);
                        boolean open = false;
                        for (Purchase p : purchases) {
                            if (p.getProducts().contains(PRODUCT) && p.getPurchaseState() == Purchase.PurchaseState.PENDING) open = true;
                        }
                        if (!open && consuming == 0) prefs.edit().remove(KEY_OPEN).apply(); // nothing left to check
                    }
                    if (settling && consuming == 0) end();
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
                // Google Play completes it later. It is consumed at the next start of atvOS (settle).
                prefs.edit().putBoolean(KEY_OPEN, true).apply();
                set(State.PENDING);
                continue;
            }
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            consuming++;
            BillingClient asked = client;
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                    (done, token) -> main.post(() -> {
                        consuming--;
                        if (done.getResponseCode() == BillingClient.BillingResponseCode.OK) set(State.THANKS);
                        // The next settle() checks again: it ends when Google Play has nothing open.
                        if (settling && client == asked && consuming == 0) end();
                    }));
        }
    }

    private void set(State s) {
        state = s;
        if (listener != null) listener.changed();
    }
}
