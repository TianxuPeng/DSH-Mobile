package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DshPageTest {

    @Test
    public void recognisesBuiltInPairingUrlAndRoot() {
        assertTrue(DshPage.isDshEntry("http://192.168.0.101:43127/pair?token=abc"));
        assertTrue(DshPage.isDshEntry("http://192.168.0.101:43127/"));
        assertTrue(DshPage.isDshEntry("https://192.168.0.101/pair?key=abc"));
        assertTrue(DshPage.isDshEntry("https://example.test/mobile/pair#token=abc"));
    }

    @Test
    public void ignoresNormalWebPages() {
        assertFalse(DshPage.isDshEntry("https://example.com/"));
        assertFalse(DshPage.isDshEntry("https://example.com/pair"));
        assertFalse(DshPage.isDshEntry("https://example.com/articles?token=abc"));
        assertNull(DshPage.originOf("javascript:alert(1)"));
    }

    @Test
    public void normalisesOriginsAndBuildsBridgeUrl() {
        assertEquals("http://192.168.0.101:43127", DshPage.originOf("http://192.168.0.101:43127/pair?token=abc"));
        assertEquals("https://example.com:443", DshPage.originOf("https://example.com/path"));
        assertEquals("http://192.168.0.101:43130/balance",
                DshPage.balanceBridgeUrl("http://192.168.0.101:43127"));
        assertEquals("http://[fe80::1]:43130/balance",
                DshPage.balanceBridgeUrl("http://[fe80::1]:43127"));
        assertEquals("http://192.168.0.101:43130/refresh",
                DshPage.balanceBridgeRefreshUrl("http://192.168.0.101:43127"));
    }

    @Test
    public void formatsMoneyAndPricingLabels() {
        assertEquals("¥31.38", DshPage.amountText("CNY", "31.38"));
        assertEquals("$5.00", DshPage.amountText("USD", "5.00"));
        assertEquals("31.38", DshPage.amountText("", "31.38"));
        assertEquals("--", DshPage.amountText("CNY", ""));
        assertEquals("峰", DshPage.tierLabel("peak"));
        assertEquals("谷", DshPage.tierLabel("idle"));
        assertEquals("--", DshPage.tierLabel("unknown"));
    }
}
