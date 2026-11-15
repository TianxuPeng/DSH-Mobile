package com.dshmobile.app;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Decides which QR payloads may be opened by the built-in browser.
 *
 * <p>Only normal web URLs are accepted. QR codes carrying arbitrary text,
 * javascript:, data:, file:, intent: and other app-launching schemes are
 * rejected before they reach WebView.</p>
 */
final class UrlPolicy {

    private static final int MAX_URL_LENGTH = 8192;

    private UrlPolicy() {
    }

    /**
     * @return a safe http/https URL, or {@code null} when the payload should
     * not be opened.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }

        String text = raw.trim();
        if (text.isEmpty() || text.length() > MAX_URL_LENGTH) {
            return null;
        }
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1).trim();
        }
        if (text.isEmpty() || text.length() > MAX_URL_LENGTH) {
            return null;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c == 0x7F) {
                // Spaces and control characters are never valid in a QR web URL.
                return null;
            }
        }

        boolean explicitScheme = startsWithIgnoreCase(text, "http://")
                || startsWithIgnoreCase(text, "https://");

        String candidate;
        if (explicitScheme) {
            candidate = text;
        } else {
            if (text.contains("://")) {
                return null;
            }
            if (!looksLikeBareHostUrl(text)) {
                return null;
            }
            candidate = "https://" + text;
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        if (uri.isOpaque()) {
            return null;
        }

        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || authority.contains("@")) {
            // User-info makes the real destination ambiguous without a URL bar.
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            host = rawHost(authority);
        }
        if (host == null || host.isEmpty() || !isValidHost(host)) {
            return null;
        }

        if (!explicitScheme && host.indexOf('.') < 0) {
            return null;
        }

        int port = uri.getPort();
        if (port > 65535) {
            return null;
        }

        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals(lowerScheme)) {
            candidate = lowerScheme + "://" + candidate.substring(candidate.indexOf("://") + 3);
        }
        return candidate;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * For a scheme-less QR payload, require something that looks like a real
     * authority with a dot. This keeps ordinary text from being turned into a
     * URL by accident.
     */
    private static boolean looksLikeBareHostUrl(String text) {
        int end = text.length();
        int slash = text.indexOf('/');
        int query = text.indexOf('?');
        int fragment = text.indexOf('#');
        if (slash >= 0) {
            end = Math.min(end, slash);
        }
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        String authority = text.substring(0, end);
        if (authority.isEmpty() || authority.contains("@")) {
            return false;
        }
        String host = rawHost(authority);
        return host != null && host.indexOf('.') >= 0;
    }

    private static String rawHost(String authority) {
        String hostPort = authority;
        if (hostPort.startsWith("[")) {
            int closingBracket = hostPort.indexOf(']');
            if (closingBracket < 0) {
                return null;
            }
            return hostPort.substring(0, closingBracket + 1);
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            hostPort = hostPort.substring(0, colon);
        }
        return hostPort;
    }

    private static boolean isValidHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            String address = host.substring(1, host.length() - 1);
            return address.indexOf(':') > 0;
        }

        String normalizedHost = host;
        if (normalizedHost.startsWith(".") || normalizedHost.endsWith(".")) {
            return false;
        }

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(normalizedHost, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (asciiHost.isEmpty() || asciiHost.length() > 253) {
            return false;
        }

        String[] labels = asciiHost.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean valid = (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '-';
                if (!valid) {
                    return false;
                }
            }
        }
        return true;
    }
}
