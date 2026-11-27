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

        requestConsentThenStartAds();
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
        public void retry() { main.post(() -> web.loadUrl(BuildConfig.GAME_URL)); }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
}
