package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UrlPolicyTest {

    @Test
    public void acceptsWebUrls() {
        assertEquals("https://example.com/a?b=c", UrlPolicy.normalize("https://example.com/a?b=c"));
        assertEquals("http://example.com", UrlPolicy.normalize("  http://example.com  "));
        assertEquals("https://example.com", UrlPolicy.normalize("example.com"));
        assertEquals("https://www.example.com/path", UrlPolicy.normalize("www.example.com/path"));
        assertEquals("https://example.com:8443/x", UrlPolicy.normalize("example.com:8443/x"));
        assertEquals("http://EXAMPLE.COM/A", UrlPolicy.normalize("HTTP://EXAMPLE.COM/A"));
        assertEquals("http://192.168.1.10:8080/x", UrlPolicy.normalize("http://192.168.1.10:8080/x"));
    }

    @Test
    public void rejectsNonWebOrAmbiguousPayloads() {
        assertNull(UrlPolicy.normalize("hello world"));
        assertNull(UrlPolicy.normalize("普通文本，不是链接"));
        assertNull(UrlPolicy.normalize("javascript:alert(1)"));
        assertNull(UrlPolicy.normalize("JavaScript:alert(1)"));
        assertNull(UrlPolicy.normalize("data:text/html,<h1>x</h1>"));
        assertNull(UrlPolicy.normalize("file:///sdcard/a.html"));
        assertNull(UrlPolicy.normalize("content://com.example/x"));
        assertNull(UrlPolicy.normalize("intent://scan/#Intent;scheme=zxing;end"));
        assertNull(UrlPolicy.normalize("ftp://example.com/file"));
        assertNull(UrlPolicy.normalize("mailto:a@example.com"));
        assertNull(UrlPolicy.normalize("https://"));
        assertNull(UrlPolicy.normalize("https://user:pass@example.com"));
        assertNull(UrlPolicy.normalize("https://example .com/"));
        assertNull(UrlPolicy.normalize("https://example.com:99999/"));
        assertNull(UrlPolicy.normalize("java\nscript:alert(1)"));
        assertNull(UrlPolicy.normalize(null));
    }
}
