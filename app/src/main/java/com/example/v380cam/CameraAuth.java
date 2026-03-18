package com.example.v380cam;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * Mirrors GeneratePassword() from v380.cpp (github.com/mzyy94/v380)
 * Double AES-128 ECB:
 *   1. Encrypt password with static key "macrovideo+*#!^@"
 *   2. Re-encrypt with random 16-char printable key
 *   Output = randomKey(16) + encryptedData
 */
public class CameraAuth {

    private static final String STATIC_KEY = "macrovideo+*#!^@";
    private static final String CHARSET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890!@#$%^&*()_+-=";

    public static byte[] generatePassword(String password) throws Exception {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++)
            sb.append(CHARSET.charAt(rng.nextInt(CHARSET.length())));
        String randomKey = sb.toString();

        byte[] pwBytes = password.getBytes("ISO-8859-1");
        int    pad     = 16 - (pwBytes.length % 16);
        byte[] padded  = new byte[pwBytes.length + pad];
        System.arraycopy(pwBytes, 0, padded, 0, pwBytes.length);

        Cipher c1 = Cipher.getInstance("AES/ECB/NoPadding");
        c1.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(STATIC_KEY.getBytes("ISO-8859-1"), "AES"));
        byte[] enc1 = c1.doFinal(padded);

        Cipher c2 = Cipher.getInstance("AES/ECB/NoPadding");
        c2.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(randomKey.getBytes("ISO-8859-1"), "AES"));
        byte[] enc2 = c2.doFinal(enc1);

        byte[] rkb    = randomKey.getBytes("ISO-8859-1");
        byte[] output = new byte[rkb.length + enc2.length];
        System.arraycopy(rkb,  0, output, 0,          rkb.length);
        System.arraycopy(enc2, 0, output, rkb.length, enc2.length);
        return output;
    }
}
