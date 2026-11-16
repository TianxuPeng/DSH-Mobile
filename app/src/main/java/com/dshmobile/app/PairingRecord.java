package com.dshmobile.app;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

/** One remembered DSH Mobile pairing. The token is encrypted at rest. */
final class PairingRecord {

    final String origin;
    final String instanceId;
    final String deviceToken;
    final long deviceExpiresAt;
    final byte[] caCertificate;
    final String displayName;

    PairingRecord(
            String origin,
            String instanceId,
            String deviceToken,
            long deviceExpiresAt,
            byte[] caCertificate,
            String displayName) {
        this.origin = origin;
        this.instanceId = instanceId;
        this.deviceToken = deviceToken;
        this.deviceExpiresAt = deviceExpiresAt;
        this.caCertificate = caCertificate == null ? null : caCertificate.clone();
        this.displayName = displayName;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("version", 1)
                .put("origin", origin)
                .put("instanceId", instanceId)
                .put("deviceToken", deviceToken)
                .put("deviceExpiresAt", deviceExpiresAt)
                .put("caCertificate", caCertificate == null
                        ? "" : Base64.encodeToString(caCertificate, Base64.NO_WRAP))
                .put("displayName", displayName == null ? "" : displayName);
    }

    static PairingRecord fromJson(JSONObject json) throws JSONException {
        if (json.optInt("version", 0) != 1) {
            return null;
        }
        String origin = json.getString("origin");
        String instanceId = json.getString("instanceId");
        String deviceToken = json.getString("deviceToken");
        long expires = json.getLong("deviceExpiresAt");
        String ca = json.optString("caCertificate", "");
        String name = json.optString("displayName", "");
        if (!origin.startsWith("https://")
                || !instanceId.matches("^[a-f0-9]{64}$")
                || !deviceToken.matches("^[A-Za-z0-9_-]{43}$")
                || expires <= 0) {
            return null;
        }
        byte[] caBytes = null;
        if (!ca.isEmpty()) {
            caBytes = Base64.decode(ca, Base64.NO_WRAP);
        }
        return new PairingRecord(origin, instanceId, deviceToken, expires, caBytes, name);
    }
}
