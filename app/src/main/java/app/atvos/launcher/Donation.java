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
 * Settings > Support > Donate, through Google Play billing: a consumable in-app product,
 * {@link #PRODUCT}, priced per country in Play Console (₹99, $4.99, £4.99, 9,99 zł, ¥499, ₱99,
 * ₩4,999). It's used up as soon as Play confirms it, so it can be given again, and it unlocks nothing.
 * <p>
 * Play is only contacted once Settings > Support is opened, until Settings closes ({@link #start}
 * to {@link #end}): nothing at launch, nothing in the background. If Play can't be reached it
 * reports itself unavailable. UI thread; Play's callbacks are handed back to it.
 */
final class Donation {

    /** The in-app product's id in Play Console. */
    static final String PRODUCT = "donation";

    /** Where things stand, for the Settings row. */
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

    /** The price as Play shows it in this country ("₹99"), once known. */
    String price() {
        ProductDetails.OneTimePurchaseOfferDetails offer = product != null ? product.getOneTimePurchaseOfferDetails() : null;
        return offer != null ? offer.getFormattedPrice() : null;
    }

    /** Connects to Google Play and looks up the donation's price; {@code listener} hears of each change. */
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
                        if (client != connecting) return; // ended meanwhile
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
                        client = null; // connect again the next time Settings opens
                        product = null;
                        if (state != State.THANKS) set(State.UNAVAILABLE);
                    });
                }
            });
        } catch (RuntimeException e) {
            client = null;
            set(State.UNAVAILABLE); // Google Play isn't there to talk to
        }
    }

    /** Settings closed: let Play go. */
    void end() {
        listener = null;
        if (client != null) client.endConnection();
        client = null;
        product = null;
        if (state != State.THANKS) state = State.UNAVAILABLE;
    }

    /** Opens Google Play's own purchase sheet for the donation. */
    void give() {
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
            set(product != null && price() != null ? State.READY : State.UNAVAILABLE);
        }));
    }

    /** A donation paid for earlier but never used up (the app closed first, or it was pending): finish it now. */
    private void finishEarlier() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> main.post(() -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) consumeAll(purchases);
                }));
    }

    private void purchased(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) consumeAll(purchases);
        // Cancelled, or an error Play has already shown: nothing more to say.
    }

    private void consumeAll(List<Purchase> purchases) {
        if (purchases == null || client == null) return;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                set(State.PENDING); // paid by a slower method: Play finishes it, then it's used up next time
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
