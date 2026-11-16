package com.dshmobile.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dsh-mobile pairing QR: an HTTPS URL to /mobile-access/pair plus either the
 * current dsh2 key fragment or the legacy instance/token fragment.
 *
 * <p>This is the same link format consumed by the dsh-mobile Android app. The
 * one-time token is never persisted; only the resulting device token is.</p>
 */
final class PairingLink {

    private static final Pattern KEY_FRAGMENT = Pattern.compile(
            "^key=dsh([12])\\.([a-f0-9]{64})\\.([A-Za-z0-9_-]{43})$");
    private static final Pattern LEGACY_FRAGMENT = Pattern.compile(
            "^instance=([a-f0-9]{64})&token=([A-Za-z0-9_-]{43})$");
    private static final int MAX_LENGTH = 8192;

    final String origin;
    final String host;
    final String instanceId;
    final String token;
    final boolean requiresCa;

    private PairingLink(String origin, String host, String instanceId, String token, boolean requiresCa) {
        this.origin = origin;
        this.host = host;
        this.instanceId = instanceId;
        this.token = token;
        this.requiresCa = requiresCa;
    }

    static PairingLink parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || !"/mobile-access/pair".equals(uri.getPath())) {
            return null;
        }
        String fragment = uri.getRawFragment();
        if (fragment == null) {
            return null;
        }

        String instanceId;
        String token;
        boolean requiresCa;
        Matcher key = KEY_FRAGMENT.matcher(fragment);
        Matcher legacy = LEGACY_FRAGMENT.matcher(fragment);
        if (key.matches()) {
            requiresCa = "2".equals(key.group(1));
            instanceId = key.group(2);
            token = key.group(3);
        } else if (legacy.matches()) {
            requiresCa = false;
            instanceId = legacy.group(1);
            token = legacy.group(2);
        } else {
            return null;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            return null;
        }
        String renderedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        String origin = "https://" + renderedHost + (port == -1 || port == 443 ? "" : ":" + port);
        return new PairingLink(origin, host, instanceId, token, requiresCa);
    }
}
