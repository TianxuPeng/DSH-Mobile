package com.dshmobile.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.RequiresApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately chrome-less browser. There is no URL bar, no back/forward
 * buttons, no home button and no tab switcher. On a page opened from a DSH
 * pairing QR, a slim native status bar shows the DeepSeek balance and the
 * current peak/off-peak pricing period. The bar has no navigation controls and
 * cannot be expanded.
 */
public final class BrowserActivity extends ComponentActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_SESSION_READY = "extra_session_ready";

    private static final int REQUEST_FILE_CHOOSER = 5201;
    private static final String BALANCE_TOKEN = "5cecb92087ef557a70d52d9be55c226e";
    private static final long ACTIVE_BALANCE_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long JS_QUERY_FALLBACK_MS = 9_000L;
    private static final long FAST_RETRY_INTERVAL_MS = 10_000L;
    private static final int MAX_FAST_RETRIES = 6;

    /**
     * Sent inside the authenticated DSH page every five minutes. It asks
     * dsh-pet to refresh the real DeepSeek balance, then reads the refreshed
     * state from the same origin. Because it runs in the page (not on a
     * separate LAN port), it uses whatever transport the page already uses:
     * direct Wi-Fi, an HTTPS remote gateway, or a mobile-data tunnel.
     */
    private static final String ACTIVE_QUERY_JS_TEMPLATE =
            "(function(){"
                    + "var qid='__QID__';var done=false;"
                    + "function finish(o){if(done)return;done=true;try{DshMobileBalance.onBalance(qid,JSON.stringify(o));}catch(e){}}"
                    + "var ctl=(typeof AbortController!=='undefined')?new AbortController():null;"
                    + "var timer=setTimeout(function(){if(ctl)try{ctl.abort();}catch(e){}finish({ok:false,error:'timeout'});},7000);"
                    + "var opts={method:'POST',headers:{'Content-Type':'application/json'},body:'{}',credentials:'same-origin'};"
                    + "if(ctl)opts.signal=ctl.signal;"
                    + "function legacy(){fetch('/dsh-pet-7340/balance',opts)"
                    + ".then(function(r){if(!r.ok)throw new Error('balance '+r.status);return fetch('/dsh-pet-7340/state',{cache:'no-store',credentials:'same-origin'});})"
                    + ".then(function(r){if(!r.ok)throw new Error('state '+r.status);return r.json();})"
                    + ".then(function(j){clearTimeout(timer);var s=j&&j.sections&&j.sections.balance&&j.sections.balance.data;"
                    + "if(!s||s.ok!==true)throw new Error((s&&(s.message||s.reason))||'dsh-pet unavailable');"
                    + "var w=(s.data&&s.data.wallets)||[];var pick=null;"
                    + "for(var i=0;i<w.length;i++){if(w[i]&&String(w[i].currency)==='CNY'){pick=w[i];break;}}"
                    + "if(!pick)for(var k=0;k<w.length;k++){if(w[k]&&String(w[k].currency)==='USD'){pick=w[k];break;}}"
                    + "if(!pick&&w.length)pick=w[0];"
                    + "if(!pick||pick.total===undefined||pick.total===null)throw new Error('no wallet');"
                    + "finish({ok:true,currency:String(pick.currency||''),total:String(pick.total),tier:String((s.data&&s.data.tier)||'')});"
                    + "}).catch(function(e){clearTimeout(timer);finish({ok:false,error:String(e)});});"
                    + "}"
                    + "fetch('/mobile-access/extensions/balance-title/routes/refresh',{cache:'no-store',credentials:'same-origin',signal:ctl?ctl.signal:undefined})"
                    + ".then(function(r){if(!r.ok)throw new Error('extension '+r.status);return r.json();})"
                    + ".then(function(j){clearTimeout(timer);if(!j||j.ok!==true||j.total===undefined||j.total===null)throw new Error('extension balance unavailable');"
                    + "finish({ok:true,currency:String(j.currency||''),total:String(j.total),tier:String(j.tier||'')});})"
                    + ".catch(function(){legacy();});"
                    + "})();";

    private FrameLayout root;
    private WebView webView;
    private ProgressBar loading;
    private LinearLayout errorPanel;
    private TextView errorDetail;
    private LinearLayout balanceBar;
    private TextView balanceAmount;
    private TextView balanceTier;
    private FrameLayout customViewContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;
    private AlertDialog jsDialog;
    private Handler balanceHandler;
    private ExecutorService balanceExecutor;
    private ExecutorService pairingExecutor;
    private String dshOrigin;
    private String pairedOrigin;
    private byte[] pinnedCa;
    private PairingRecord pairingRecord;
    private String lastUrl;
    private String previousPageUrl;
    private long lastBlockedToastAt;
    private boolean resumed;
    private boolean pageReady;
    private boolean balancePolling;
    private boolean activeQueryInFlight;
    private int activeQueryId;
    private int fastRetryCount;
    private Runnable activeQueryFallback;

    private final Runnable balancePoll = new Runnable() {
        @Override
        public void run() {
            if (!balancePolling) {
                return;
            }
            requestActiveBalance();
            if (balancePolling && balanceHandler != null) {
                balanceHandler.postDelayed(this, ACTIVE_BALANCE_INTERVAL_MS);
            }
        }
    };

    /** Retries a few times after a failed query, so a just-completed pairing
     * is picked up without waiting for the next five-minute tick. */
    private final Runnable fastRetry = new Runnable() {
        @Override
        public void run() {
            if (balancePolling) {
                requestActiveBalance();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = UrlPolicy.normalize(getIntent().getStringExtra(EXTRA_URL));
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        lastUrl = url;

        PairingLink pairingLink = PairingLink.parse(url);
        if (pairingLink != null) {
            Intent pairingIntent = new Intent(this, PairingActivity.class);
            pairingIntent.putExtra(PairingActivity.EXTRA_PAIR_URL, url);
            startActivity(pairingIntent);
            finish();
            return;
        }
        PairingStore store = new PairingStore(this);
        PairingRecord stored = store.load();
        String origin = DshPage.originOf(url);
        if (stored != null && stored.origin.equals(origin)) {
            pairingRecord = stored;
            pairedOrigin = stored.origin;
            pinnedCa = stored.caCertificate == null ? null : stored.caCertificate.clone();
        }

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(R.layout.activity_browser);

        root = findViewById(R.id.browser_root);
        webView = findViewById(R.id.web_view);
        loading = findViewById(R.id.loading);
        errorPanel = findViewById(R.id.browser_error_panel);
        errorDetail = findViewById(R.id.browser_error_detail);
        balanceBar = findViewById(R.id.balance_bar);
        balanceAmount = findViewById(R.id.balance_amount);
        balanceTier = findViewById(R.id.balance_tier);
        balanceHandler = new Handler(Looper.getMainLooper());
        balanceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dsh-balance");
            thread.setDaemon(true);
            return thread;
        });
        pairingExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dsh-pairing-session");
            thread.setDaemon(true);
            return thread;
        });
        resetBalanceBar();

        findViewById(R.id.browser_retry).setOnClickListener(view -> retry());
        findViewById(R.id.browser_back).setOnClickListener(view -> finish());

        configureWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView();
                } else if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        updateDshMode(url);
        loading.setVisibility(View.VISIBLE);
        boolean sessionReady = getIntent().getBooleanExtra(EXTRA_SESSION_READY, false);
        if (pairingRecord != null && !sessionReady) {
            renewSessionAndLoad(url);
        } else {
            webView.loadUrl(url);
        }
    }

    private void renewSessionAndLoad(String url) {
        if (pairingRecord == null || pairingExecutor == null) {
            showPairingExpired();
            return;
        }
        pairingExecutor.execute(() -> {
            try {
                NativeAuthClient.Session session = NativeAuthClient.renew(pairingRecord);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    SessionCookies.install(pairedOrigin, session,
                            () -> {
                                if (!isFinishing() && !isDestroyed() && webView != null) {
                                    loading.setVisibility(View.VISIBLE);
                                    webView.loadUrl(url);
                                }
                            },
                            () -> showError(getString(R.string.pairing_cookie_failed)));
                });
            } catch (Exception e) {
                runOnUiThread(this::showPairingExpired);
            }
        });
    }

    private void showPairingExpired() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        loading.setVisibility(View.GONE);
        showError(getString(R.string.browser_pairing_expired));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView.setBackgroundColor(Color.BLACK);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppWebChromeClient());
        webView.addJavascriptInterface(new BalanceJsInterface(), "DshMobileBalance");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                Toast.makeText(this, R.string.download_not_supported, Toast.LENGTH_SHORT).show());
    }

    private final class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl(), request.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url), true);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            previousPageUrl = lastUrl;
            if (isHttpUrl(url)) {
                lastUrl = url;
            }
            pageReady = false;
            stopBalancePolling();
            updateDshMode(url);
            hideError();
            loading.setVisibility(View.VISIBLE);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            boolean leftPairingPage = previousPageUrl != null
                    && DshPage.isPairingPage(previousPageUrl)
                    && !DshPage.isPairingPage(url);
            if (isHttpUrl(url)) {
                lastUrl = url;
            }
            updateDshMode(url);
            if (leftPairingPage && resumed && pageReady && balanceBar != null
                    && balanceBar.getVisibility() == View.VISIBLE) {
                fastRetryCount = 0;
                cancelFastRetry();
                requestActiveBalance();
            }
            previousPageUrl = url;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            loading.setVisibility(View.GONE);
            pageReady = true;
            updateDshMode(url);
            if (resumed && balanceBar != null
                    && balanceBar.getVisibility() == View.VISIBLE) {
                startBalancePolling();
            }
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                loading.setVisibility(View.GONE);
                showError(describeError(error.getErrorCode()));
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(
                WebView view, SslErrorHandler handler, SslError error) {
            if (pinnedCa != null && pairedOrigin != null
                    && error != null
                    && error.getPrimaryError() == SslError.SSL_UNTRUSTED
                    && PinnedTls.isPinnedAuthority(pairedOrigin, error.getUrl())) {
                X509Certificate leaf = PinnedTls.leafFromSslCertificate(error.getCertificate());
                if (PinnedTls.acceptsWebViewLeaf(pairedOrigin, pinnedCa, leaf)) {
                    handler.proceed();
                    return;
                }
            }
            handler.cancel();
            loading.setVisibility(View.GONE);
            showError(getString(R.string.ssl_error));
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Toast.makeText(BrowserActivity.this, R.string.render_gone, Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
    }

    private final class AppWebChromeClient extends WebChromeClient {

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            stopBalancePolling();
            balanceBar.setVisibility(View.GONE);

            customViewContainer = new FrameLayout(BrowserActivity.this);
            customViewContainer.setBackgroundColor(Color.BLACK);
            customViewContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(customViewContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            webView.setVisibility(View.INVISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> newCallback,
                FileChooserParams fileChooserParams) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = newCallback;

            Intent intent;
            try {
                intent = fileChooserParams.createIntent();
            } catch (RuntimeException e) {
                filePathCallback = null;
                newCallback.onReceiveValue(null);
                Toast.makeText(BrowserActivity.this,
                        R.string.file_chooser_unavailable, Toast.LENGTH_SHORT).show();
                return false;
            }

            try {
                startActivityForResult(intent, REQUEST_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                newCallback.onReceiveValue(null);
                Toast.makeText(BrowserActivity.this,
                        R.string.file_chooser_unavailable, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            if (request != null) {
                request.deny();
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback) {
            if (callback != null) {
                callback.invoke(origin, false, false);
            }
        }

        @Override
        public boolean onJsAlert(
                WebView view, String url, String message, JsResult result) {
            return showJsDialog(
                    new AlertDialog.Builder(BrowserActivity.this)
                            .setTitle(R.string.js_dialog_title)
                            .setMessage(message)
                            .setPositiveButton(R.string.js_confirm_ok,
                                    (dialog, which) -> result.confirm())
                            .setOnCancelListener(dialog -> result.cancel()),
                    result);
        }

        @Override
        public boolean onJsConfirm(
                WebView view, String url, String message, JsResult result) {
            return showJsDialog(
                    new AlertDialog.Builder(BrowserActivity.this)
                            .setTitle(R.string.js_dialog_title)
                            .setMessage(message)
                            .setPositiveButton(R.string.js_confirm_ok,
                                    (dialog, which) -> result.confirm())
                            .setNegativeButton(R.string.js_confirm_cancel,
                                    (dialog, which) -> result.cancel())
                            .setOnCancelListener(dialog -> result.cancel()),
                    result);
        }

        @Override
        public boolean onJsPrompt(
                WebView view,
                String url,
                String message,
                String defaultValue,
                JsPromptResult result) {
            EditText input = new EditText(BrowserActivity.this);
            input.setSingleLine(false);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setHint(R.string.js_prompt_hint);
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            input.setPadding(padding, padding / 2, padding, padding / 2);

            AlertDialog.Builder builder = new AlertDialog.Builder(BrowserActivity.this)
                    .setTitle(R.string.js_dialog_title)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(R.string.js_confirm_ok,
                            (dialog, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(R.string.js_confirm_cancel,
                            (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel());
            return showJsDialog(builder, result);
        }

        private boolean showJsDialog(AlertDialog.Builder builder, JsResult result) {
            if (isFinishing() || isDestroyed()) {
                result.cancel();
                return true;
            }
            try {
                AlertDialog dialog = builder.create();
                dialog.setOnDismissListener(d -> jsDialog = null);
                jsDialog = dialog;
                dialog.show();
            } catch (RuntimeException e) {
                jsDialog = null;
                result.cancel();
            }
            return true;
        }
    }

    private boolean handleNavigation(Uri uri, boolean mainFrame) {
        if (uri == null) {
            return true;
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (mainFrame) {
            long now = System.currentTimeMillis();
            if (now - lastBlockedToastAt > 1500) {
                lastBlockedToastAt = now;
                Toast.makeText(this, R.string.scheme_blocked, Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String scheme = Uri.parse(url).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * Shows the balance status bar only while the current page belongs to the
     * DSH pairing origin. dshOrigin is kept for the whole browsing session, so
     * going back from an external link restores the bar.
     */
    private void updateDshMode(String url) {
        if (balanceBar == null || url == null) {
            return;
        }
        if (customView != null) {
            stopBalancePolling();
            balanceBar.setVisibility(View.GONE);
            return;
        }

        String currentOrigin = DshPage.originOf(url);
        if (currentOrigin == null) {
            hideBalanceBar();
            return;
        }

        boolean entry = DshPage.isDshEntry(url)
                || (pairedOrigin != null && pairedOrigin.equals(currentOrigin));
        if (dshOrigin == null) {
            if (entry) {
                dshOrigin = currentOrigin;
                resetBalanceBar();
                showBalanceBar();
            } else {
                hideBalanceBar();
            }
            return;
        }

        if (currentOrigin.equals(dshOrigin)) {
            showBalanceBar();
            return;
        }

        if (entry) {
            stopBalancePolling();
            dshOrigin = currentOrigin;
            resetBalanceBar();
            showBalanceBar();
            return;
        }

        hideBalanceBar();
    }

    private void showBalanceBar() {
        if (balanceBar == null) {
            return;
        }
        balanceBar.setVisibility(View.VISIBLE);
        if (resumed && pageReady) {
            startBalancePolling();
        }
    }

    private void hideBalanceBar() {
        if (balanceBar == null) {
            return;
        }
        stopBalancePolling();
        balanceBar.setVisibility(View.GONE);
        resetBalanceBar();
    }

    private void resetBalanceBar() {
        if (balanceAmount == null || balanceTier == null) {
            return;
        }
        balanceAmount.setText(R.string.balance_unknown);
        balanceTier.setText(R.string.balance_unknown);
        balanceTier.setBackgroundResource(R.drawable.bg_tier_unknown);
        balanceTier.setTextColor(getColor(R.color.text_secondary));
        balanceBar.setContentDescription(getString(
                R.string.balance_accessibility,
                getString(R.string.balance_unknown),
                getString(R.string.balance_unknown)));
    }

    private void startBalancePolling() {
        if (balancePolling || !resumed || !pageReady || dshOrigin == null
                || balanceBar == null || balanceBar.getVisibility() != View.VISIBLE) {
            return;
        }
        balancePolling = true;
        fastRetryCount = 0;
        cancelFastRetry();
        requestActiveBalance();
        balanceHandler.postDelayed(balancePoll, ACTIVE_BALANCE_INTERVAL_MS);
    }

    private void stopBalancePolling() {
        balancePolling = false;
        activeQueryInFlight = false;
        activeQueryId++;
        fastRetryCount = 0;
        if (balanceHandler != null) {
            balanceHandler.removeCallbacks(balancePoll);
            cancelActiveQueryFallback();
            cancelFastRetry();
        }
    }

    private void cancelFastRetry() {
        if (balanceHandler != null) {
            balanceHandler.removeCallbacks(fastRetry);
        }
    }

    private void completeActiveQuery(boolean success) {
        activeQueryInFlight = false;
        cancelActiveQueryFallback();
        if (success) {
            boolean recoveredAfterRetry = fastRetryCount > 0;
            fastRetryCount = 0;
            cancelFastRetry();
            if (recoveredAfterRetry && balancePolling && balanceHandler != null) {
                // The first usable balance may arrive shortly after pairing.
                // Measure the five-minute cadence from that successful query.
                balanceHandler.removeCallbacks(balancePoll);
                balanceHandler.postDelayed(balancePoll, ACTIVE_BALANCE_INTERVAL_MS);
            }
            return;
        }
        if (!balancePolling || !resumed || !pageReady || dshOrigin == null
                || balanceBar == null || balanceBar.getVisibility() != View.VISIBLE
                || fastRetryCount >= MAX_FAST_RETRIES) {
            return;
        }
        fastRetryCount++;
        balanceHandler.postDelayed(fastRetry, FAST_RETRY_INTERVAL_MS);
    }

    /**
     * Active five-minute query: first ask dsh-pet through the authenticated
     * page (works over Wi-Fi and mobile data), then fall back to the LAN
     * bridge only if the page cannot reach the desktop route.
     */
    private void requestActiveBalance() {
        if (!resumed || !pageReady || dshOrigin == null || webView == null
                || balanceBar == null || balanceBar.getVisibility() != View.VISIBLE
                || activeQueryInFlight) {
            return;
        }
        String currentOrigin = DshPage.originOf(currentPageUrl());
        if (currentOrigin == null || !currentOrigin.equals(dshOrigin)) {
            return;
        }

        activeQueryInFlight = true;
        final int queryId = ++activeQueryId;
        activeQueryFallback = () -> startNativeBalanceFallback(queryId);
        balanceHandler.postDelayed(activeQueryFallback, JS_QUERY_FALLBACK_MS);
        try {
            webView.evaluateJavascript(
                    ACTIVE_QUERY_JS_TEMPLATE.replace("__QID__", String.valueOf(queryId)),
                    null);
        } catch (RuntimeException e) {
            startNativeBalanceFallback(queryId);
        }
    }

    private void cancelActiveQueryFallback() {
        if (activeQueryFallback != null && balanceHandler != null) {
            balanceHandler.removeCallbacks(activeQueryFallback);
        }
        activeQueryFallback = null;
    }

    private final class BalanceJsInterface {
        @JavascriptInterface
        public void onBalance(String queryId, String payload) {
            if (balanceHandler == null) {
                return;
            }
            balanceHandler.post(() -> handleJsBalanceResult(queryId, payload));
        }
    }

    private void handleJsBalanceResult(String queryIdText, String payload) {
        int queryId;
        try {
            queryId = Integer.parseInt(queryIdText);
        } catch (NumberFormatException e) {
            return;
        }
        if (!activeQueryInFlight || queryId != activeQueryId) {
            return;
        }
        if (!isCurrentDshPage()) {
            activeQueryInFlight = false;
            cancelActiveQueryFallback();
            return;
        }

        try {
            JSONObject result = new JSONObject(payload);
            if (result.optBoolean("ok", false)) {
                String currency = result.optString("currency", "");
                String total = result.optString("total", "");
                String tier = result.optString("tier", "");
                if (total.isEmpty()) {
                    throw new JSONException("empty balance");
                }
                if (balanceBar.getVisibility() == View.VISIBLE) {
                    applyBalance(currency, total, tier);
                }
                completeActiveQuery(true);
                return;
            }
        } catch (JSONException ignored) {
            // Fall through to the LAN bridge.
        }
        startNativeBalanceFallback(queryId);
    }

    private boolean isCurrentDshPage() {
        if (webView == null || dshOrigin == null) {
            return false;
        }
        return dshOrigin.equals(DshPage.originOf(currentPageUrl()));
    }

    private String currentPageUrl() {
        if (webView != null) {
            String current = webView.getUrl();
            if (current != null && !current.isEmpty()) {
                return current;
            }
        }
        return lastUrl;
    }

    private void startNativeBalanceFallback(int queryId) {
        if (!activeQueryInFlight || queryId != activeQueryId || !isCurrentDshPage()) {
            return;
        }
        cancelActiveQueryFallback();
        if (dshOrigin == null || balanceExecutor == null) {
            showBalanceUnknown();
            completeActiveQuery(false);
            return;
        }
        String bridgeUrl = DshPage.balanceBridgeRefreshUrl(dshOrigin);
        if (bridgeUrl == null) {
            showBalanceUnknown();
            completeActiveQuery(false);
            return;
        }

        balanceExecutor.execute(() -> {
            boolean ok = false;
            String currency = "";
            String total = "";
            String tier = "";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(bridgeUrl).openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(6000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("X-DSH-Balance-Key", BALANCE_TOKEN);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    String body = readLimited(connection.getInputStream());
                    JSONObject json = new JSONObject(body);
                    if (json.optBoolean("ok", false)) {
                        currency = json.optString("currency", "");
                        total = json.optString("total", "");
                        tier = json.optString("tier", "");
                        ok = !total.isEmpty();
                    }
                }
            } catch (Exception ignored) {
                // The title bar intentionally shows "--" on any failure.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final boolean resultOk = ok;
            final String resultCurrency = currency;
            final String resultTotal = total;
            final String resultTier = tier;
            if (balanceHandler == null) {
                return;
            }
            balanceHandler.post(() -> {
                if (!activeQueryInFlight || queryId != activeQueryId) {
                    return;
                }
                if (balanceBar != null && balanceBar.getVisibility() == View.VISIBLE) {
                    if (resultOk) {
                        applyBalance(resultCurrency, resultTotal, resultTier);
                    } else {
                        showBalanceUnknown();
                    }
                }
                completeActiveQuery(resultOk);
            });
        });
    }

    private void applyBalance(String currency, String total, String tier) {
        if (balanceAmount == null || balanceTier == null) {
            return;
        }
        String amount = DshPage.amountText(currency, total);
        String tierLabel = DshPage.tierLabel(tier);
        balanceAmount.setText(amount);
        balanceTier.setText(tierLabel);
        if ("peak".equalsIgnoreCase(tier)) {
            balanceTier.setBackgroundResource(R.drawable.bg_tier_peak);
            balanceTier.setTextColor(getColor(R.color.peak));
        } else if ("idle".equalsIgnoreCase(tier) || "valley".equalsIgnoreCase(tier)) {
            balanceTier.setBackgroundResource(R.drawable.bg_tier_valley);
            balanceTier.setTextColor(getColor(R.color.valley));
        } else {
            balanceTier.setBackgroundResource(R.drawable.bg_tier_unknown);
            balanceTier.setTextColor(getColor(R.color.text_secondary));
        }
        balanceBar.setContentDescription(getString(
                R.string.balance_accessibility, amount, tierLabel));
    }

    private void showBalanceUnknown() {
        if (balanceAmount == null || balanceTier == null) {
            return;
        }
        balanceAmount.setText(R.string.balance_unknown);
        balanceTier.setText(R.string.balance_unknown);
        balanceTier.setBackgroundResource(R.drawable.bg_tier_unknown);
        balanceTier.setTextColor(getColor(R.color.text_secondary));
        balanceBar.setContentDescription(getString(
                R.string.balance_accessibility,
                getString(R.string.balance_unknown),
                getString(R.string.balance_unknown)));
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > 16 * 1024) {
                    throw new IOException("response too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String describeError(int errorCode) {
        switch (errorCode) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return getString(R.string.browser_error_host);
            case WebViewClient.ERROR_CONNECT:
                return getString(R.string.browser_error_connect);
            case WebViewClient.ERROR_TIMEOUT:
                return getString(R.string.browser_error_timeout);
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                return getString(R.string.browser_error_ssl_handshake);
            case WebViewClient.ERROR_UNSUPPORTED_SCHEME:
                return getString(R.string.browser_error_unsupported);
            default:
                return getString(R.string.browser_error_unknown);
        }
    }

    private void showError(String detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        errorDetail.setText(detail);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    private void retry() {
        hideError();
        if (lastUrl == null) {
            finish();
            return;
        }
        if (pairingRecord != null && pairedOrigin != null
                && pairedOrigin.equals(DshPage.originOf(lastUrl))) {
            loading.setVisibility(View.VISIBLE);
            renewSessionAndLoad(lastUrl);
            return;
        }
        loading.setVisibility(View.VISIBLE);
        webView.loadUrl(lastUrl);
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        customView = null;
        if (customViewContainer != null) {
            root.removeView(customViewContainer);
            customViewContainer = null;
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            updateDshMode(webView.getUrl() != null ? webView.getUrl() : lastUrl);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(parseFileChooserResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private Uri[] parseFileChooserResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return null;
        }
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            Uri[] uris = new Uri[count];
            for (int i = 0; i < count; i++) {
                uris[i] = data.getClipData().getItemAt(i).getUri();
            }
            return uris;
        }
        if (data.getData() != null) {
            return new Uri[]{data.getData()};
        }
        return null;
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopBalancePolling();
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (webView != null) {
            webView.onResume();
            updateDshMode(webView.getUrl() != null ? webView.getUrl() : lastUrl);
        }
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        stopBalancePolling();
        if (balanceExecutor != null) {
            balanceExecutor.shutdownNow();
            balanceExecutor = null;
        }
        if (pairingExecutor != null) {
            pairingExecutor.shutdownNow();
            pairingExecutor = null;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (jsDialog != null) {
            jsDialog.dismiss();
            jsDialog = null;
        }
        if (customView != null) {
            hideCustomView();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
