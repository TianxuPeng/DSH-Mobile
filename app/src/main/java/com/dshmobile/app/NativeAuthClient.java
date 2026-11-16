package com.dshmobile.app;

import android.annotation.SuppressLint;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Native pairing and renewal for the dsh-mobile gateway. The protocol is the
 * same one used by the official Android app:
 *
 * <pre>
 * GET  /mobile-access/ca.cer
 * POST /mobile-access/auth/native-pair   {token,label}
 * POST /mobile-access/auth/native-renew  {deviceToken}
 * </pre>
 */
final class NativeAuthClient {

    private static final int MAX_CA_BYTES = 16 * 1024;
    private static final int MAX_SESSION_BYTES = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private NativeAuthClient() {
    }

    static final class Session {
        final String origin;
        final String instanceId;
        final String deviceId;
        final String deviceToken;
        final long deviceExpiresAt;
        final String sessionToken;
        final String csrfToken;
        final long sessionExpiresAt;

        Session(
                String origin,
                String instanceId,
                String deviceId,
                String deviceToken,
                long deviceExpiresAt,
                String sessionToken,
                String csrfToken,
                long sessionExpiresAt) {
            this.origin = origin;
            this.instanceId = instanceId;
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
            this.deviceExpiresAt = deviceExpiresAt;
            this.sessionToken = sessionToken;
            this.csrfToken = csrfToken;
            this.sessionExpiresAt = sessionExpiresAt;
        }
    }

    static final class PairingException extends Exception {
        PairingException(String message) {
            super(message);
        }
    }

    /** Fetch the gateway's pairing CA through a chain-only bootstrap channel. */
    static byte[] fetchCa(PairingLink link) throws PairingException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(
                    link.origin + "/mobile-access/ca.cer").openConnection();
            connection.setSSLSocketFactory(trustAllContext().getSocketFactory());
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/x-x509-ca-cert, application/octet-stream");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                if (link.requiresCa) {
                    throw new PairingException("电脑证书端点不存在，无法校验 dsh-mobile 配对身份。");
                }
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new PairingException("读取电脑证书失败（HTTP " + code + "）。");
            }
            byte[] der = readLimited(connection.getInputStream(), MAX_CA_BYTES);
            validateCa(der, link.instanceId);
            return der;
        } catch (PairingException e) {
            throw e;
        } catch (Exception e) {
            throw new PairingException("无法读取电脑证书，请确认连接的是自己的 DSH。");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Session pair(PairingLink link, byte[] ca) throws PairingException {
        JSONObject body = new JSONObject();
        try {
            body.put("token", link.token);
            body.put("label", "dshMobile");
        } catch (JSONException e) {
            throw new PairingException("无法构造配对请求。");
        }
        return post(link.origin, "/mobile-access/auth/native-pair", body, ca,
                link.instanceId, true);
    }

    static Session renew(PairingRecord record) throws PairingException {
        JSONObject body = new JSONObject();
        try {
            body.put("deviceToken", record.deviceToken);
        } catch (JSONException e) {
            throw new PairingException("无法构造续期请求。");
        }
        return post(record.origin, "/mobile-access/auth/native-renew", body,
                record.caCertificate, record.instanceId, false);
    }

    private static Session post(
            String origin,
            String path,
            JSONObject body,
            byte[] ca,
            String expectedInstanceId,
            boolean requireDeviceCredential) throws PairingException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(origin + path).openConnection();
            if (ca != null) {
                connection.setSSLSocketFactory(PinnedTls.socketFactory(ca));
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Origin", origin);
            connection.setRequestProperty("Sec-Fetch-Site", "same-origin");
            connection.setRequestProperty("X-DSH-Mobile-Capabilities", "installation-id");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new PairingException(statusMessage(code));
            }
            byte[] data = readLimited(connection.getInputStream(), MAX_SESSION_BYTES);
            return parseSession(data, origin, expectedInstanceId, requireDeviceCredential);
        } catch (PairingException e) {
            throw e;
        } catch (Exception e) {
            throw new PairingException("无法连接电脑，请检查 Wi-Fi 或远程通道。");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Session parseSession(
            byte[] bytes,
            String origin,
            String expectedInstanceId,
            boolean requireDeviceCredential) throws PairingException {
        try {
            JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String instanceId = requiredString(response, "instanceId", "^[a-f0-9]{64}$");
            if (!instanceId.equals(expectedInstanceId)) {
                throw new PairingException("电脑身份与二维码不一致，已拒绝连接。");
            }
            String deviceId = requiredString(response, "deviceId", "^[A-Za-z0-9_-]{1,128}$");
            String sessionToken = requiredString(response, "sessionToken", "^[A-Za-z0-9_-]{43}$");
            String csrfToken = requiredString(response, "csrfToken", "^[A-Za-z0-9_-]{43}$");
            long sessionExpiresAt = response.getLong("sessionExpiresAt");
            if (sessionExpiresAt <= System.currentTimeMillis()) {
                throw new PairingException("电脑返回的会话已过期。");
            }
            String deviceToken = null;
            long deviceExpiresAt = 0L;
            if (requireDeviceCredential) {
                deviceToken = requiredString(response, "deviceToken", "^[A-Za-z0-9_-]{43}$");
                deviceExpiresAt = response.getLong("deviceExpiresAt");
                if (deviceExpiresAt <= System.currentTimeMillis()) {
                    throw new PairingException("电脑返回的设备授权已过期。");
                }
            }
            return new Session(origin, instanceId, deviceId, deviceToken, deviceExpiresAt,
                    sessionToken, csrfToken, sessionExpiresAt);
        } catch (PairingException e) {
            throw e;
        } catch (Exception e) {
            throw new PairingException("电脑返回了无法识别的配对响应。");
        }
    }

    private static String requiredString(JSONObject object, String key, String pattern)
            throws PairingException {
        Object value = object.opt(key);
        if (!(value instanceof String) || !((String) value).matches(pattern)) {
            throw new PairingException("电脑返回了无法识别的配对响应。");
        }
        return (String) value;
    }

    private static String statusMessage(int code) {
        if (code == 401 || code == 403) {
            return "配对码已失效，请在电脑上重新生成二维码。";
        }
        if (code == 409) {
            return "电脑的已配对设备数量已达上限。";
        }
        if (code == 429) {
            return "配对请求过于频繁，请稍后再试。";
        }
        if (code >= 500) {
            return "电脑暂时不可用（HTTP " + code + "）。";
        }
        return "配对失败（HTTP " + code + "）。";
    }

    private static void validateCa(byte[] der, String expectedInstanceId) throws PairingException {
        try {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            ca.checkValidity();
            if (ca.getBasicConstraints() < 0
                    || !ca.getSubjectX500Principal().equals(ca.getIssuerX500Principal())) {
                throw new PairingException("电脑证书不是有效的配对 CA。");
            }
            ca.verify(ca.getPublicKey());
            String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(ca.getEncoded()));
            if (!fingerprint.equals(expectedInstanceId)) {
                throw new PairingException("电脑证书指纹与二维码不一致，已拒绝连接。");
            }
        } catch (PairingException e) {
            throw e;
        } catch (Exception e) {
            throw new PairingException("电脑证书无效，已拒绝连接。");
        }
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers, null);
        return context;
    }

    private static byte[] readLimited(InputStream input, int max) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("response too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
