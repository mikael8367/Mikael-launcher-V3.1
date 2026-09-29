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
    private static final String ALIAS="mikael_launcher_accounts_v1";
    private static final String PREFIX="mkaes1:";
    private AccountSecureStore(){}

    private static SecretKey key() throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }

    public static String protect(String plain){
        if(plain==null||plain.isEmpty()||"0".equals(plain)||plain.startsWith(PREFIX))return plain;
        try{
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return PREFIX+Base64.encodeToString(iv,Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        }catch(Exception e){throw new IllegalStateException("Unable to protect account token",e);}
    }

    public static String restore(String stored){
        if(stored==null||!stored.startsWith(PREFIX))return stored;
        try{
            String[] p=stored.substring(PREFIX.length()).split(":",2);if(p.length!=2)return "0";
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){return "0";}
    }
}
