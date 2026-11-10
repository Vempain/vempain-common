package fi.poltsi.vempain.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Shared token generation and hashing primitives. Backends retain their own prefixes and lifecycle policy.
 */
public final class ApiTokenCrypto {

    private static final int TOKEN_BYTES = 32;

    private ApiTokenCrypto() {}

    public static String generate(String prefix) {
        var bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return prefix + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                .formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
