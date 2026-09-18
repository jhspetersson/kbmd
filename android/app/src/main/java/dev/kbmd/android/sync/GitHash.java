package dev.kbmd.android.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class GitHash {

    private GitHash() {
    }

    /** The id Git gives a file with this content, which is what both providers report in their tree listings. */
    static String blob(byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            byte[] digest = sha1.digest(content);
            StringBuilder hex = new StringBuilder(40);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
