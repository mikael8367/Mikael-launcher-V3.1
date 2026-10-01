package net.kdt.pojavlaunch;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AccountSecureStore {
    private static final String ALIAS = "mikael_launcher_accounts_v1";
    private static final String PREFIX = "mkaes1:";
    private AccountSecureStore() {}

    private static SecretKey generateKey(KeyStore store) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return gen.generateKey();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);

        if (store.containsAlias(ALIAS)) {
            try {
                KeyStore.Entry entry = store.getEntry(ALIAS, null);
                if (entry instanceof KeyStore.SecretKeyEntry) {
                    SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                    if (key != null) return key;
                }
            } catch (Throwable ignored) {
                // The Android Keystore key may have been invalidated after
                // restore, OS update, lock-screen/security changes, or app
                // data migration. Recreate it below.
            }

            try {
                store.deleteEntry(ALIAS);
            } catch (Throwable ignored) {}
        }

        return generateKey(store);
    }

    private static SecretKey keyWithRecovery() throws Exception {
        try {
            return key();
        } catch (Throwable first) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            try { store.deleteEntry(ALIAS); } catch (Throwable ignored) {}
            return generateKey(store);
        }
    }

    public static String protect(String plain) {
        if (plain == null || plain.isEmpty() || "0".equals(plain) || plain.startsWith(PREFIX)) {
            return plain;
        }

        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyWithRecovery(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Throwable error) {
            throw new IllegalStateException("Unable to protect account token", error);
        }
    }

    public static String restore(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;

        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return "0";

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyWithRecovery(),
                    new GCMParameterSpec(
                            128,
                            Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(
                    cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // The old ciphertext may belong to a key that Android invalidated.
            // Treat it as unavailable so the account can authenticate again.
            return "0";
        }
    }
}
