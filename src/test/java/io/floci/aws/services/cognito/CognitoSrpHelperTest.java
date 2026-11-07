package io.floci.aws.services.cognito;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoSrpHelperTest {

    @Test
    void verifySignatureUsesShortPoolNameNotFullPoolId() {
        String fullPoolId = "us-east-1_TestPool123";
        String poolName   = "TestPool123";
        String username   = "alice";
        String password   = "Password1!";
        String saltHex    = CognitoSrpHelper.generateSalt();

        // Store verifier with short name (as CognitoService does)
        String verifierHex = CognitoSrpHelper.computeVerifier(poolName, username, password, saltHex);

        // Generate server B
        String[] serverB  = CognitoSrpHelper.generateServerB(verifierHex);
        String bHex       = serverB[0];
        String bPublicHex = serverB[1];

        // Simulate a client A (use a known non-trivial value)
        BigInteger a      = new BigInteger(256, new SecureRandom());
        BigInteger A      = CognitoSrpHelper.G.modPow(a, CognitoSrpHelper.N);
        String aHex       = A.toString(16);

        // Compute session key (server side)
        byte[] sessionKey = CognitoSrpHelper.computeSessionKey(aHex, bHex, bPublicHex, verifierHex);

        // Simulate client behavior: compute signature using short pool name
        // (This is what the fix enables: the server now also uses the short name
        // even if passed the full pool ID).
        byte[] secretBlock = new byte[16];
        new SecureRandom().nextBytes(secretBlock);
        String timestamp = "Wed Apr  9 12:00:00 UTC 2026";
        
        // Manual client-side computation (mocking how Amplify/SDK does it)
        // In real AWS SRP, the message fed to HMAC uses the short pool name.
        byte[] sig = CognitoSrpHelper.computeSignature(sessionKey, poolName, username, secretBlock, timestamp);
        String sigBase64 = Base64.getEncoder().encodeToString(sig);

        // verifySignature must accept the full pool ID and match correctly
        assertTrue(CognitoSrpHelper.verifySignature(sessionKey, fullPoolId, username, secretBlock, timestamp, sigBase64),
                "Signature verification failed when using full pool ID");
    }
}
