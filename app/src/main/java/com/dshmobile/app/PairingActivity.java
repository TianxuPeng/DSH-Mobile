package com.dshmobile.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Performs the same native pairing handshake as the official dsh-mobile
 * Android app, then opens the paired origin in the built-in WebView.
 */
public final class PairingActivity extends ComponentActivity {

    public static final String EXTRA_PAIR_URL = "extra_pair_url";

    private ExecutorService executor;
    private volatile boolean active;
    private ProgressBar progress;
    private TextView status;
    private TextView error;
    private Button back;
    private PairingLink link;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        progress = findViewById(R.id.pairing_progress);
        status = findViewById(R.id.pairing_status);
        error = findViewById(R.id.pairing_error);
        back = findViewById(R.id.pairing_back);
        findViewById(R.id.pairing_cancel).setOnClickListener(view -> finish());
        back.setOnClickListener(view -> finish());
        active = true;

        link = PairingLink.parse(getIntent().getStringExtra(EXTRA_PAIR_URL));
        if (link == null) {
            showFailure(getString(R.string.pairing_invalid_link));
            return;
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dsh-pairing");
            thread.setDaemon(true);
            return thread;
        });
        startPairing();
    }

    private void startPairing() {
        progress.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        back.setVisibility(View.GONE);
        status.setText(R.string.pairing_working);
        executor.execute(() -> {
            try {
                PairingStore store = new PairingStore(this);
                PairingRecord existing = store.load();
                byte[] ca = NativeAuthClient.fetchCa(link);
                NativeAuthClient.Session session = null;
                PairingRecord record = null;

                if (existing != null
                        && existing.origin.equals(link.origin)
                        && existing.instanceId.equals(link.instanceId)) {
                    try {
                        session = NativeAuthClient.renew(existing);
                        record = new PairingRecord(
                                existing.origin,
                                existing.instanceId,
                                existing.deviceToken,
                                existing.deviceExpiresAt,
                                ca != null ? ca : existing.caCertificate,
                                existing.displayName);
                        store.save(record);
                    } catch (NativeAuthClient.PairingException ignored) {
                        session = null;
                    }
                }

                if (session == null) {
                    session = NativeAuthClient.pair(link, ca);
                    if (session.deviceToken == null) {
                        throw new NativeAuthClient.PairingException(
                                "电脑没有返回设备授权，无法完成配对。");
                    }
                    record = new PairingRecord(
                            link.origin,
                            session.instanceId,
                            session.deviceToken,
                            session.deviceExpiresAt,
                            ca,
                            "DeepSeek Harness");
                    if (!store.save(record)) {
                        throw new NativeAuthClient.PairingException(
                                getString(R.string.pairing_storage_failed));
                    }
                }

                final NativeAuthClient.Session ready = session;
                final PairingRecord paired = record;
                runOnUiThread(() -> {
                    if (!active || isFinishing() || isDestroyed()) {
                        return;
                    }
                    status.setText(R.string.pairing_success);
                    SessionCookies.install(paired.origin, ready,
                            () -> openBrowser(paired.origin),
                            () -> showFailure(getString(R.string.pairing_cookie_failed)));
                });
            } catch (NativeAuthClient.PairingException e) {
                runOnUiThread(() -> showFailure(e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> showFailure(getString(R.string.pairing_failed)
                        + "：" + (e.getMessage() == null ? "" : e.getMessage())));
            }
        });
    }

    private void openBrowser(String origin) {
        if (!active || isFinishing() || isDestroyed()) {
            return;
        }
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_URL, origin + "/");
        intent.putExtra(BrowserActivity.EXTRA_SESSION_READY, true);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
        overridePendingTransition(0, 0);
    }

    private void showFailure(String message) {
        if (!active || isFinishing() || isDestroyed()) {
            return;
        }
        progress.setVisibility(View.GONE);
        status.setText(R.string.pairing_failed);
        error.setText(message == null || message.isEmpty()
                ? getString(R.string.pairing_failed) : message);
        error.setVisibility(View.VISIBLE);
        back.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        active = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        super.onDestroy();
    }
}
