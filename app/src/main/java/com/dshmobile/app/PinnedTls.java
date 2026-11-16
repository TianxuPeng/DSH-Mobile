package com.dshmobile.app;

import android.net.http.SslCertificate;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Per-pairing CA pinning for pairing HTTP calls and the paired WebView origin. */
final class PinnedTls {

    private PinnedTls() {
    }

    static SSLSocketFactory socketFactory(byte[] caDer) throws Exception {
        X509Certificate ca = certificate(caDer);
        ca.checkValidity();
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        store.setCertificateEntry("dsh-mobile", ca);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    static boolean isPinnedAuthority(String pairedOrigin, String candidate) {
        if (pairedOrigin == null || candidate == null) {
            return false;
        }
        try {
            URI pinned = new URI(pairedOrigin);
            URI url = new URI(candidate);
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"wss".equals(scheme)) {
                return false;
            }
            if (url.getRawUserInfo() != null || url.getHost() == null) {
                return false;
            }
            String pinnedHost = pinned.getHost();
            if (pinnedHost == null) {
                return false;
            }
            if (pinnedHost.startsWith("[") && pinnedHost.endsWith("]")) {
                pinnedHost = pinnedHost.substring(1, pinnedHost.length() - 1);
            }
            String urlHost = url.getHost();
            if (urlHost.startsWith("[") && urlHost.endsWith("]")) {
                urlHost = urlHost.substring(1, urlHost.length() - 1);
            }
            if (!pinnedHost.equalsIgnoreCase(urlHost)) {
                return false;
            }
            int pinnedPort = pinned.getPort() >= 0 ? pinned.getPort() : 443;
            int urlPort = url.getPort() >= 0 ? url.getPort() : 443;
            return pinnedPort == urlPort;
        } catch (Exception e) {
            return false;
        }
    }

    static X509Certificate leafFromSslCertificate(SslCertificate certificate) {
        if (certificate == null) {
            return null;
        }
        try {
            android.os.Bundle state = SslCertificate.saveState(certificate);
            byte[] der = state == null ? null : state.getByteArray("x509-certificate");
            if (der == null) {
                return null;
            }
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean acceptsWebViewLeaf(String pairedOrigin, byte[] caDer, X509Certificate leaf) {
        if (pairedOrigin == null || caDer == null || leaf == null) {
            return false;
        }
        try {
            URI origin = new URI(pairedOrigin);
            String host = origin.getHost();
            if (host == null) {
                return false;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            X509Certificate ca = certificate(caDer);
            ca.checkValidity();
            leaf.checkValidity();
            if (ca.getBasicConstraints() < 0 || leaf.getBasicConstraints() >= 0) {
                return false;
            }
            if (!ca.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
                return false;
            }
            leaf.verify(ca.getPublicKey());
            List<String> usages = leaf.getExtendedKeyUsage();
            if (usages != null && !usages.contains("1.3.6.1.5.5.7.3.1")) {
                return false;
            }
            return matchesSubjectAlternativeName(host, leaf);
        } catch (Exception e) {
            return false;
        }
    }

    private static X509Certificate certificate(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean matchesSubjectAlternativeName(String host, X509Certificate certificate) {
        java.util.Collection<List<?>> alternatives;
        try {
            alternatives = certificate.getSubjectAlternativeNames();
        } catch (Exception e) {
            return false;
        }
        if (alternatives == null) {
            return false;
        }
        if (host.indexOf(':') >= 0 || host.matches("^(?:\\d{1,3}\\.){3}\\d{1,3}$")) {
            byte[] expected;
            try {
                expected = InetAddress.getByName(host).getAddress();
            } catch (Exception e) {
                return false;
            }
            for (List<?> alternative : alternatives) {
                if (alternative.size() >= 2
                        && Integer.valueOf(7).equals(alternative.get(0))
                        && alternative.get(1) instanceof String) {
                    try {
                        if (java.util.Arrays.equals(
                                InetAddress.getByName((String) alternative.get(1)).getAddress(),
                                expected)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // Try the next SAN.
                    }
                }
            }
            return false;
        }
        String canonical = host.toLowerCase(Locale.ROOT);
        for (List<?> alternative : alternatives) {
            if (alternative.size() < 2
                    || !Integer.valueOf(2).equals(alternative.get(0))
                    || !(alternative.get(1) instanceof String)) {
                continue;
            }
            String candidate = ((String) alternative.get(1)).toLowerCase(Locale.ROOT);
            if (candidate.equals(canonical)) {
                return true;
            }
            if (candidate.startsWith("*.")
                    && canonical.endsWith(candidate.substring(1))
                    && countDots(canonical) == countDots(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static int countDots(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }
}
