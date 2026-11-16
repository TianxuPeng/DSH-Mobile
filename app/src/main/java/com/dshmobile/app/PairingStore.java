package com.dshmobile.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the single last-paired DSH computer. The device token and CA are
 * encrypted with a non-exportable Android Keystore AES-GCM key.
 */
final class PairingStore {

    private static final String PREFS = "dshmobile_pairing";
    private static final String KEY_RECORD = "record_v1";
    private static final String KEY_ALIAS = "dshmobile_pairing_key";
    private static final int GCM_TAG_BITS = 128;

    private final Context context;

    PairingStore(Context context) {
        this.context = context.getApplicationContext();
    }

    PairingRecord load() {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(KEY_RECORD, null);
        if (stored == null) {
            return null;
        }
        try {
            byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
            if (packed.length < 13) {
                return null;
            }
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, 12);
            System.arraycopy(packed, 12, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return PairingRecord.fromJson(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("ApplySharedPref")
    boolean save(PairingRecord record) {
        if (record == null) {
            return false;
        }
        try {
            byte[] plain = record.toJson().toString().getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RECORD, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }

    void clear() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORD).commit();
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
}
