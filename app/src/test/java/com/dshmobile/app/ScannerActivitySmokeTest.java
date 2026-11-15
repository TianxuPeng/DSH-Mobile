package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.view.View;

import androidx.camera.view.PreviewView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScannerActivitySmokeTest {

    @Test
    public void scannerLayoutInflatesWithoutChrome() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        ActivityController<ScannerActivity> controller =
                Robolectric.buildActivity(ScannerActivity.class).create();
        ScannerActivity activity = controller.get();

        assertNotNull(activity.findViewById(R.id.preview_view));
        assertNotNull(activity.findViewById(R.id.scan_overlay));
        assertNotNull(activity.findViewById(R.id.torch_button));
        assertEquals(View.GONE, activity.findViewById(R.id.panel).getVisibility());
        assertFalse(activity.isFinishing());

        PreviewView preview = activity.findViewById(R.id.preview_view);
        assertEquals(PreviewView.ScaleType.FILL_CENTER, preview.getScaleType());

        controller.destroy();
    }
}
