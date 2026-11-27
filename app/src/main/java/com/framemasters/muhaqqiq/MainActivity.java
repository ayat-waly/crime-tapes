package com.framemasters.muhaqqiq;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the web game full screen and, when the game asks (after a case is solved),
 * one full-screen AdMob ad.
 */
public class MainActivity extends AppCompatActivity {

    private static final String OFFLINE_PAGE =
            "<html dir='rtl'><body style='background:#120c06;color:#e3d3a8;font-family:sans-serif;"
            + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>"
            + "<div><h2>لا يوجد اتصال بالإنترنت</h2><p>تأكد من الاتصال ثم اضغط إعادة المحاولة</p>"
            + "<button onclick='CTAndroid.retry()' style='font-size:18px;padding:10px 26px;border-radius:10px;"
            + "border:0;background:#8b1d1d;color:#fff'>إعادة المحاولة</button></div></body></html>";

    private WebView web;
    private InterstitialAd interstitial;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- monthly subscription (Google Play Billing) ----
    private BillingClient billing;
    private ProductDetails subDetails;
    private volatile boolean premium;
    private volatile String subPrice = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF120C06);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // keep the game clear of the status and navigation bars (edge-to-edge on Android 15+)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // keeps players' progress (localStorage)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new Bridge(), "CTAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String game = Uri.parse(BuildConfig.GAME_URL).getHost();
                if (u.getHost() != null && u.getHost().equals(game)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) view.loadDataWithBaseURL(null, OFFLINE_PAGE, "text/html", "utf-8", null);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(BuildConfig.GAME_URL);

        premium = getPreferences(MODE_PRIVATE).getBoolean("premium", false);
        startBilling();
        requestConsentThenStartAds();
    }

    private void startBilling() {
        billing = BillingClient.newBuilder(this)
                .setListener((result, purchases) -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) handlePurchases(purchases);
                })
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
        billing.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(@NonNull BillingResult r) {
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                refreshPurchases();
                loadSubscriptionDetails();
            }
            @Override public void onBillingServiceDisconnected() { }
        });
    }

    private void loadSubscriptionDetails() {
        QueryProductDetailsParams q = QueryProductDetailsParams.newBuilder().setProductList(Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder().setProductId(BuildConfig.SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS).build())).build();
        billing.queryProductDetailsAsync(q, (r, res) -> {
            List<ProductDetails> list = res.getProductDetailsList();
            if (list == null || list.isEmpty()) return;
            subDetails = list.get(0);
            try {
                List<ProductDetails.SubscriptionOfferDetails> offers = subDetails.getSubscriptionOfferDetails();
                List<ProductDetails.PricingPhase> phases = offers.get(offers.size() - 1).getPricingPhases().getPricingPhaseList();
                subPrice = phases.get(phases.size() - 1).getFormattedPrice();
            } catch (Exception ignored) { }
            notifyGame();
        });
    }

    private void refreshPurchases() {
        billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (r, purchases) -> { if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) handlePurchases(purchases); });
    }

    private void handlePurchases(List<Purchase> purchases) {
        boolean active = false;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(BuildConfig.SUBSCRIPTION_ID)) continue;
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            active = true;
            if (!p.isAcknowledged()) {
                billing.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), r -> { });
            }
        }
        setPremium(active);
    }

    private void setPremium(boolean value) {
        premium = value;
        getPreferences(MODE_PRIVATE).edit().putBoolean("premium", value).apply();
        notifyGame();
    }

    private void notifyGame() {
        main.post(() -> { if (web != null) web.evaluateJavascript("window.ctPremiumChanged&&window.ctPremiumChanged()", null); });
    }

    private void launchSubscribe() {
        if (premium) return;
        if (subDetails == null || subDetails.getSubscriptionOfferDetails() == null || subDetails.getSubscriptionOfferDetails().isEmpty()) {
            loadSubscriptionDetails();
            web.evaluateJavascript("window.toast&&toast('الاشتراك غير متاح الآن — حاول بعد قليل',true)", null);
            return;
        }
        String token = subDetails.getSubscriptionOfferDetails().get(0).getOfferToken();
        BillingFlowParams params = BillingFlowParams.newBuilder().setProductDetailsParamsList(Collections.singletonList(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(subDetails).setOfferToken(token).build())).build();
        billing.launchBillingFlow(this, params);
    }

    private void openManageSubscription() {
        Uri u = Uri.parse("https://play.google.com/store/account/subscriptions?sku=" + BuildConfig.SUBSCRIPTION_ID + "&package=" + getPackageName());
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
    }

    /** GDPR / privacy consent (required by AdMob in some regions), then start the ad SDK. */
    private void requestConsentThenStartAds() {
        ConsentInformation consent = UserMessagingPlatform.getConsentInformation(this);
        consent.requestConsentInfoUpdate(this, new ConsentRequestParameters.Builder().build(),
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
                    if (consent.canRequestAds()) startAds();
                }),
                requestError -> { if (consent.canRequestAds()) startAds(); });
        if (consent.canRequestAds()) startAds();
    }

    private void startAds() {
        if (!adsStarted.compareAndSet(false, true)) return;
        new Thread(() -> MobileAds.initialize(this, status -> main.post(this::loadInterstitial))).start();
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, BuildConfig.INTERSTITIAL_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) { interstitial = ad; }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        interstitial = null;
                        main.postDelayed(MainActivity.this::loadInterstitial, 60_000);
                    }
                });
    }

    private void showInterstitial() {
        if (premium) return;                            // subscribers never see ads
        InterstitialAd ad = interstitial;
        if (ad == null) { loadInterstitial(); return; }   // no ad ready: the player just continues
        interstitial = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { loadInterstitial(); }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) { loadInterstitial(); }
        });
        ad.show(this);
    }

    /** Called from the game's JavaScript as window.CTAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public void showInterstitial() { main.post(MainActivity.this::showInterstitial); }

        @JavascriptInterface
        public boolean isPremium() { return premium; }

        @JavascriptInterface
        public String subscriptionPrice() { return subPrice; }

        @JavascriptInterface
        public void subscribe() { main.post(MainActivity.this::launchSubscribe); }

        @JavascriptInterface
        public void manageSubscription() { main.post(MainActivity.this::openManageSubscription); }

        @JavascriptInterface
        public void retry() { main.post(() -> web.loadUrl(BuildConfig.GAME_URL)); }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() {
        super.onResume(); web.onResume();
        if (billing != null && billing.isReady()) refreshPurchases();   // picks up renewals and cancellations
    }
}
