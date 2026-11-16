package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairingLinkTest {

    private static final String INSTANCE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TOKEN =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";

    @Test
    public void parsesCurrentDsh2PairingQr() {
        PairingLink link = PairingLink.parse(
                "https://192.168.0.101:3443/mobile-access/pair#key=dsh2."
                        + INSTANCE + "." + TOKEN);
        assertEquals("https://192.168.0.101:3443", link.origin);
        assertEquals("192.168.0.101", link.host);
        assertEquals(INSTANCE, link.instanceId);
        assertEquals(TOKEN, link.token);
        assertTrue(link.requiresCa);
    }

    @Test
    public void parsesLegacyInstanceTokenQr() {
        PairingLink link = PairingLink.parse(
                "https://dsh.example.com/mobile-access/pair#instance="
                        + INSTANCE + "&token=" + TOKEN);
        assertEquals("https://dsh.example.com", link.origin);
        assertEquals("dsh.example.com", link.host);
        assertEquals(INSTANCE, link.instanceId);
        assertEquals(TOKEN, link.token);
        assertFalse(link.requiresCa);
    }

    @Test
    public void rejectsNonPairingOrInsecureLinks() {
        assertNull(PairingLink.parse(
                "http://192.168.0.101:3443/mobile-access/pair#key=dsh2."
                        + INSTANCE + "." + TOKEN));
        assertNull(PairingLink.parse(
                "https://192.168.0.101:3443/mobile-access/pair?x=1#key=dsh2."
                        + INSTANCE + "." + TOKEN));
        assertNull(PairingLink.parse(
                "https://192.168.0.101:3443/other#key=dsh2." + INSTANCE + "." + TOKEN));
        assertNull(PairingLink.parse(
                "https://192.168.0.101:3443/mobile-access/pair#key=dsh2."
                        + INSTANCE + ".short"));
    }

    @Test
    public void dsh1KeyDoesNotRequirePrivateCa() {
        PairingLink link = PairingLink.parse(
                "https://dsh.example.com:8443/mobile-access/pair#key=dsh1."
                        + INSTANCE + "." + TOKEN);
        assertEquals("https://dsh.example.com:8443", link.origin);
        assertFalse(link.requiresCa);
    }
}
