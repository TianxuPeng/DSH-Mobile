package com.dshmobile.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Pure URL/formatting helpers for the DSH mobile title bar.
 *
 * A page is treated as a DSH page only when it comes from a DSH pairing URL
 * (path containing "pair" with a credential fragment/query) or the built-in
 * DSH mobile bridge port (43127). This keeps the balance bar off ordinary web
 * pages.
 */
final class DshPage {

    static final int BUILT_IN_MOBILE_PORT = 43127;
    static final int BALANCE_BRIDGE_PORT = 43130;

    private DshPage() {
    }

    static boolean isDshEntry(String rawUrl) {
        URI uri = parse(rawUrl);
        if (uri == null || !isHttpScheme(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        if (uri.getPort() == BUILT_IN_MOBILE_PORT) {
            return true;
        }

        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (!path.contains("pair")) {
            return false;
        }
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment().toLowerCase(Locale.ROOT);
        return hasPairingCredential(query) || hasPairingCredential(fragment);
    }

    /**
     * True only for a URL that is itself the pairing handshake, as opposed to
     * an already-connected DSH page on the same origin. Used to detect the
     * transition from "pairing" to "connected" without a full page reload.
     */
    static boolean isPairingPage(String rawUrl) {
        URI uri = parse(rawUrl);
        if (uri == null || !isHttpScheme(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (!path.contains("pair")) {
            return false;
        }
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment().toLowerCase(Locale.ROOT);
        return hasPairingCredential(query) || hasPairingCredential(fragment);
    }

    private static boolean hasPairingCredential(String value) {
        return value.contains("token=")
                || value.contains("key=")
                || value.contains("code=")
                || value.contains("pin=")
                || value.contains("pair=");
    }

    /** Normalized scheme://host:port origin, including default ports. */
    static String originOf(String rawUrl) {
        URI uri = parse(rawUrl);
        if (uri == null || !isHttpScheme(uri.getScheme()) || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + host + ":" + port;
    }

    /** The bridge is deliberately a separate HTTP port on the same PC. */
    static String balanceBridgeUrl(String origin) {
        URI uri = parse(origin);
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + BALANCE_BRIDGE_PORT + "/balance";
    }

    /** LAN fallback: ask the bridge to actively refresh dsh-pet, then read it. */
    static String balanceBridgeRefreshUrl(String origin) {
        String base = balanceBridgeUrl(origin);
        if (base == null) {
            return null;
        }
        return base.substring(0, base.length() - "/balance".length()) + "/refresh";
    }

    static String amountText(String currency, String total) {
        if (total == null || total.isEmpty()) {
            return "--";
        }
        String code = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        switch (code) {
            case "CNY":
                return "¥" + total;
            case "USD":
                return "$" + total;
            case "EUR":
                return "€" + total;
            default:
                return code.isEmpty() ? total : code + " " + total;
        }
    }

    static String tierLabel(String tier) {
        if ("peak".equalsIgnoreCase(tier)) {
            return "峰";
        }
        if ("idle".equalsIgnoreCase(tier) || "valley".equalsIgnoreCase(tier)) {
            return "谷";
        }
        return "--";
    }

    private static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static URI parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
