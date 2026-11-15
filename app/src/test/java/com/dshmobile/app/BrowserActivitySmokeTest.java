package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrowserActivitySmokeTest {

    @Test
    public void browserInflatesAsAChromeLessWebView() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_URL, "https://example.com/");
        ActivityController<BrowserActivity> controller =
                Robolectric.buildActivity(BrowserActivity.class, intent).setup();
        BrowserActivity activity = controller.get();

        WebView webView = activity.findViewById(R.id.web_view);
        assertNotNull(webView);
        assertEquals(View.VISIBLE, webView.getVisibility());
        assertTrue(webView.getSettings().getJavaScriptEnabled());
        assertFalse(activity.isFinishing());
        ShadowWebView shadowWebView = shadowOf(webView);
        assertEquals("https://example.com/", shadowWebView.getLastLoadedUrl());
        assertNull(activity.getActionBar());
        assertEquals(View.GONE, activity.findViewById(R.id.balance_bar).getVisibility());
        ViewGroup root = activity.findViewById(R.id.browser_root);
        assertEquals(1, root.getChildCount());
        assertEquals(0, countEditTexts(root));

        controller.pause().stop().destroy();
    }

    @Test
    public void dshPairingPageShowsBalanceBar() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_URL, "http://192.168.0.2:43127/pair?token=test");
        ActivityController<BrowserActivity> controller =
                Robolectric.buildActivity(BrowserActivity.class, intent).create();
        BrowserActivity activity = controller.get();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.balance_bar).getVisibility());
        assertEquals("--", ((TextView) activity.findViewById(R.id.balance_amount)).getText().toString());
        assertEquals("--", ((TextView) activity.findViewById(R.id.balance_tier)).getText().toString());

        controller.destroy();
    }

    private static int countEditTexts(View root) {
        int count = 0;
        if (root instanceof EditText) {
            count++;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countEditTexts(group.getChildAt(i));
            }
        }
        return count;
    }
}
