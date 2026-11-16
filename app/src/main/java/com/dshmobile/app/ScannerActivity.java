package com.dshmobile.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal camera scanner. A successful QR code immediately opens
 * {@link BrowserActivity}; no browser toolbar is ever shown.
 */
public final class ScannerActivity extends ComponentActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 4101;
    private static final String STATE_PERMISSION_REQUESTED = "permission_requested";
    private static final long QR_CLEAR_TIMEOUT_MS = 1500L;
    private static final long RESULT_COOLDOWN_MS = 2200L;

    private enum PanelMode {
        NONE,
        PERMISSION_REASON,
        PERMISSION_SETTINGS,
        CAMERA_ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean decoding = new AtomicBoolean(false);

    private PreviewView previewView;
    private ScanOverlayView scanOverlay;
    private TextView scannerSubtitle;
    private Button torchButton;
    private Button continueButton;
    private PairingStore pairingStore;
    private LinearLayout panel;
    private TextView panelTitle;
    private TextView panelBody;
    private Button panelPrimary;
    private Button panelSecondary;

    private final QrDecoder qrDecoder = new QrDecoder();

    private ExecutorService analysisExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private Camera camera;
    private byte[] luminanceBuffer;

    private boolean cameraStarting;
    private boolean permissionRequested;
    private boolean openingBrowser;
    private boolean resumed;
    private boolean torchOn;
    private boolean torchAvailable;
    private volatile long suppressUntil;
    private volatile boolean awaitQrClear;
    private volatile long lastQrSeenAt;
    private volatile String lastOpenedPayload;
    private PanelMode panelMode = PanelMode.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.bg));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.bg));

        previewView = findViewById(R.id.preview_view);
        scanOverlay = findViewById(R.id.scan_overlay);
        scannerSubtitle = findViewById(R.id.scanner_subtitle);
        torchButton = findViewById(R.id.torch_button);
        continueButton = findViewById(R.id.continue_button);
        pairingStore = new PairingStore(this);
        continueButton.setOnClickListener(view -> openSavedPairing());
        updateContinueButton();
        panel = findViewById(R.id.panel);
        panelTitle = findViewById(R.id.panel_title);
        panelBody = findViewById(R.id.panel_body);
        panelPrimary = findViewById(R.id.panel_primary);
        panelSecondary = findViewById(R.id.panel_secondary);

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);

        permissionRequested = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PERMISSION_REQUESTED, false);

        analysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qr-decode");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });

        scanOverlay.setScanning(false);
        panelPrimary.setOnClickListener(view -> onPanelPrimary());
        panelSecondary.setOnClickListener(view -> openAppSettings());
        torchButton.setOnClickListener(view -> toggleTorch());
        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusAt(event.getX(), event.getY());
                view.performClick();
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateContinueButton();

        if (openingBrowser) {
            openingBrowser = false;
            // If the same QR is still in view when the user comes back, do not
            // trap them in a loop of reopening it. Scanner re-arms only after
            // the code leaves the frame (or a different QR is presented).
            awaitQrClear = true;
            lastQrSeenAt = 0L;
            suppressUntil = SystemClock.elapsedRealtime() + 350L;
        }

        if (hasCameraPermission()) {
            hidePanel();
            scannerSubtitle.setText(R.string.scanner_subtitle);
            scanOverlay.setScanning(true);
            startCameraIfNeeded();
            updateTorchUi();
        } else {
            stopCameraForPermissionLoss();
            scanOverlay.setScanning(false);
            scannerSubtitle.setText(R.string.scanner_subtitle_permission);
            if (!permissionRequested) {
                requestCameraPermission();
            } else {
                showPermissionPanel();
            }
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        scanOverlay.setScanning(false);
        setTorch(false);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequested);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (RuntimeException ignored) {
            // Camera provider can already be tearing down.
        }
        camera = null;
        imageAnalysis = null;
        cameraProvider = null;
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        permissionRequested = true;
        hidePanel();
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            hidePanel();
            scannerSubtitle.setText(R.string.scanner_subtitle);
            scanOverlay.setScanning(true);
            startCameraIfNeeded();
        } else {
            showPermissionPanel();
        }
    }

    private void showPermissionPanel() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        scanOverlay.setScanning(false);
        scannerSubtitle.setText(R.string.scanner_subtitle_permission);
        panelTitle.setText(R.string.permission_title);

        boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA);
        panelBody.setText(canAskAgain
                ? R.string.permission_body
                : R.string.permission_body_denied);

        if (canAskAgain) {
            panelMode = PanelMode.PERMISSION_REASON;
            panelPrimary.setText(R.string.permission_retry);
            panelSecondary.setVisibility(View.VISIBLE);
            panelSecondary.setText(R.string.permission_settings);
        } else {
            panelMode = PanelMode.PERMISSION_SETTINGS;
            panelPrimary.setText(R.string.permission_settings);
            panelSecondary.setVisibility(View.GONE);
        }
        panel.setVisibility(View.VISIBLE);
        torchButton.setVisibility(View.GONE);
    }

    private void showCameraErrorPanel() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        panelMode = PanelMode.CAMERA_ERROR;
        scanOverlay.setScanning(false);
        scannerSubtitle.setText(R.string.camera_error_title);
        panelTitle.setText(R.string.camera_error_title);
        panelBody.setText(R.string.camera_error_body);
        panelPrimary.setText(R.string.camera_retry);
        panelSecondary.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        torchAvailable = false;
        torchOn = false;
        torchButton.setVisibility(View.GONE);
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        panelMode = PanelMode.NONE;
    }

    private void onPanelPrimary() {
        switch (panelMode) {
            case PERMISSION_REASON:
                requestCameraPermission();
                break;
            case PERMISSION_SETTINGS:
                openAppSettings();
                break;
            case CAMERA_ERROR:
                hidePanel();
                scanOverlay.setScanning(true);
                scannerSubtitle.setText(R.string.scanner_subtitle_starting);
                startCameraIfNeeded();
                break;
            default:
                break;
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.permission_settings, Toast.LENGTH_SHORT).show();
        }
    }

    private void startCameraIfNeeded() {
        if (!hasCameraPermission() || camera != null || cameraStarting) {
            return;
        }
        if (cameraProvider != null) {
            cameraStarting = true;
            try {
                bindCameraUseCases();
            } finally {
                cameraStarting = false;
            }
            return;
        }

        cameraStarting = true;
        scannerSubtitle.setText(R.string.scanner_subtitle_starting);
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                cameraStarting = false;
                return;
            }
            try {
                cameraProvider = providerFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                showCameraErrorPanel();
            } finally {
                cameraStarting = false;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || !hasCameraPermission()) {
            return;
        }

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        cameraProvider.unbindAll();
        camera = null;

        CameraSelector selector;
        try {
            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                selector = CameraSelector.DEFAULT_BACK_CAMERA;
            } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                selector = CameraSelector.DEFAULT_FRONT_CAMERA;
            } else {
                showCameraErrorPanel();
                return;
            }
        } catch (Exception e) {
            showCameraErrorPanel();
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        @SuppressWarnings("deprecation")
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyzeImage);

        try {
            Camera boundCamera = cameraProvider.bindToLifecycle(
                    this, selector, preview, analysis);
            imageAnalysis = analysis;
            camera = boundCamera;
            torchAvailable = boundCamera.getCameraInfo().hasFlashUnit();
            torchOn = false;
            updateTorchUi();
            hidePanel();
            scannerSubtitle.setText(R.string.scanner_subtitle);
            scanOverlay.setScanning(true);
        } catch (RuntimeException e) {
            analysis.clearAnalyzer();
            imageAnalysis = null;
            camera = null;
            showCameraErrorPanel();
        }
    }

    private void stopCameraForPermissionLoss() {
        setTorch(false);
        torchAvailable = false;
        torchButton.setVisibility(View.GONE);
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (RuntimeException ignored) {
            // Nothing to clean up.
        }
        camera = null;
    }

    private void toggleTorch() {
        if (camera == null || !torchAvailable) {
            Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        setTorch(!torchOn);
    }

    private void setTorch(boolean on) {
        if (camera == null) {
            torchOn = false;
            updateTorchUi();
            return;
        }
        if (!torchAvailable) {
            torchOn = false;
            updateTorchUi();
            return;
        }
        try {
            ListenableFuture<Void> future = camera.getCameraControl().enableTorch(on);
            torchOn = on;
            updateTorchUi();
            future.addListener(() -> {
                try {
                    future.get();
                } catch (Exception e) {
                    torchOn = !on;
                    updateTorchUi();
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (RuntimeException e) {
            torchOn = false;
            updateTorchUi();
            Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateTorchUi() {
        boolean visible = camera != null && torchAvailable;
        torchButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        torchButton.setText(torchOn ? R.string.torch_off : R.string.torch_on);
        torchButton.setBackgroundResource(torchOn ? R.drawable.bg_pill_on : R.drawable.bg_pill);
        torchButton.setContentDescription(getString(
                torchOn ? R.string.torch_off : R.string.torch_on));
    }

    private void focusAt(float x, float y) {
        if (camera == null) {
            return;
        }
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            previewView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (RuntimeException ignored) {
            // Some devices do not expose metering; keep scanning regardless.
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(ImageProxy imageProxy) {
        long now = SystemClock.elapsedRealtime();
        if (!resumed
                || now < suppressUntil
                || !decoding.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        try {
            if (imageProxy.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
                return;
            }
            if (awaitQrClear) {
                if (lastQrSeenAt == 0L) {
                    lastQrSeenAt = now;
                } else if (now - lastQrSeenAt > QR_CLEAR_TIMEOUT_MS) {
                    awaitQrClear = false;
                }
            }
            android.media.Image image = imageProxy.getImage();
            if (image == null || image.getPlanes().length == 0) {
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            int size = width * height;
            if (luminanceBuffer == null || luminanceBuffer.length != size) {
                luminanceBuffer = new byte[size];
            }

            if (!copyLuminance(image, width, height, luminanceBuffer)) {
                return;
            }

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            String text = qrDecoder.decode(luminanceBuffer, width, height, rotation);
            if (text != null && !text.isEmpty()) {
                if (awaitQrClear) {
                    lastQrSeenAt = SystemClock.elapsedRealtime();
                    if (text.equals(lastOpenedPayload)) {
                        // The previously opened QR is still in frame.
                        return;
                    }
                    awaitQrClear = false;
                }
                suppressUntil = SystemClock.elapsedRealtime() + RESULT_COOLDOWN_MS;
                mainHandler.post(() -> handleQrPayload(text));
            }
        } catch (RuntimeException ignored) {
            // The camera delivered a malformed frame.
        } finally {
            decoding.set(false);
            imageProxy.close();
        }
    }

    private void handleQrPayload(String payload) {
        if (isFinishing() || isDestroyed() || !resumed || openingBrowser) {
            return;
        }
        String url = UrlPolicy.normalize(payload);
        if (url == null) {
            Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_SHORT).show();
            return;
        }

        openingBrowser = true;
        awaitQrClear = false;
        lastOpenedPayload = payload;
        scanOverlay.setScanning(false);
        previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        PairingLink pairing = PairingLink.parse(url);
        Intent intent = new Intent(this, pairing == null ? BrowserActivity.class : PairingActivity.class);
        if (pairing == null) {
            intent.putExtra(BrowserActivity.EXTRA_URL, url);
        } else {
            intent.putExtra(PairingActivity.EXTRA_PAIR_URL, url);
        }
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void openSavedPairing() {
        if (isFinishing() || isDestroyed() || openingBrowser) {
            return;
        }
        PairingRecord record = pairingStore == null ? null : pairingStore.load();
        if (record == null) {
            updateContinueButton();
            return;
        }
        openingBrowser = true;
        awaitQrClear = false;
        scanOverlay.setScanning(false);
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_URL, record.origin + "/");
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void updateContinueButton() {
        if (continueButton == null) {
            return;
        }
        PairingRecord record = pairingStore == null ? null : pairingStore.load();
        continueButton.setVisibility(record == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Copies the Y plane into a tightly packed grayscale buffer. Handles the
     * row/pixel padding that different camera HALs use.
     */
    private static boolean copyLuminance(android.media.Image image, int width, int height, byte[] out) {
        android.media.Image.Plane plane = image.getPlanes()[0];
        if (plane == null) {
            return false;
        }
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int limit = buffer.limit();

        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                int offset = row * rowStride;
                if (offset >= limit) {
                    break;
                }
                int count = Math.min(width, limit - offset);
                buffer.position(offset);
                buffer.get(out, row * width, count);
            }
            return true;
        }

        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowStride;
            int outOffset = row * width;
            for (int col = 0; col < width; col++) {
                int offset = rowOffset + col * pixelStride;
                if (offset >= limit) {
                    break;
                }
                out[outOffset + col] = buffer.get(offset);
            }
        }
        return true;
    }
}
