package io.github.hectorvent.floci.services.cognito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** A client-side password proof; it uses only the challenge's public values. */
final class CognitoSrpTestClient {
    private static final BigInteger PRIVATE_A = new BigInteger("123456789abcdef123456789abcdef", 16);
    private static final BigInteger PUBLIC_A = CognitoSrpHelper.G.modPow(PRIVATE_A, CognitoSrpHelper.N);

    private CognitoSrpTestClient() {}

    static String publicA() {
        return PUBLIC_A.toString(16);
    }

    static Map<String, String> proof(String poolId, String username, String password, Map<?, ?> params) throws Exception {
        String salt = (String) params.get("SALT");
        BigInteger publicB = new BigInteger((String) params.get("SRP_B"), 16);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] inner = hash.digest((CognitoSrpHelper.extractPoolName(poolId) + username + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        hash.update(new BigInteger(salt, 16).toByteArray());
        BigInteger x = new BigInteger(1, hash.digest(inner));
        hash.update(PUBLIC_A.toByteArray());
        BigInteger u = new BigInteger(1, hash.digest(publicB.toByteArray()));
        BigInteger shared = publicB.subtract(CognitoSrpHelper.K.multiply(CognitoSrpHelper.G.modPow(x, CognitoSrpHelper.N)))
                .mod(CognitoSrpHelper.N).modPow(PRIVATE_A.add(u.multiply(x)), CognitoSrpHelper.N);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(u.toByteArray(), "HmacSHA256"));
        byte[] prk = mac.doFinal(shared.toByteArray());
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("Caldera Derived Key".getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        byte[] key = Arrays.copyOf(mac.doFinal(), 16);
        String timestamp = "Wed Apr 8 12:00:00 UTC 2026";
        return Map.of("USERNAME", username, "TIMESTAMP", timestamp,
                "PASSWORD_CLAIM_SECRET_BLOCK", (String) params.get("SECRET_BLOCK"),
                "PASSWORD_CLAIM_SIGNATURE", Base64.getEncoder().encodeToString(CognitoSrpHelper.computeSignature(key,
                        poolId, username, Base64.getDecoder().decode((String) params.get("SECRET_BLOCK")), timestamp)));
    }
}
