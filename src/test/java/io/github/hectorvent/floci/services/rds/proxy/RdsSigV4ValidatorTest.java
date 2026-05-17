package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsSigV4ValidatorTest {

    @Test
    void validateAcceptsTokenSignedByStandardSigV4() throws Exception {
        String accessKeyId = "AKIAORACLETEST";
        String secretAccessKey = "oracle-secret-key-value";
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(accessKeyId, secretAccessKey);

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = SigV4TokenTestHelper.createRdsToken(
                "db.oracle-test.local",
                5432,
                "testuser",
                accessKeyId,
                secretAccessKey,
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "testuser"),
                "Validator must accept a well-formed SigV4 RDS authentication token");
    }

    @Test
    void validateAcceptsTokenSignedWithHostAndPort() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTokenWhenSignedForHostWithoutPort() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String brokenToken = validToken.replace("db.example.local:5432/?", "db.example.local/?");

        assertFalse(validator.validate(brokenToken, "admin"));
    }

    @Test
    void validateRejectsExpiredToken() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(1200),
                900
        );

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTamperedSignature() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String tamperedToken = validToken.replace("DBUser=admin", "DBUser=attacker");

        assertFalse(validator.validate(tamperedToken, "admin"));
    }

    @Test
    void validateRejectsTokenWithUnknownAccessKey() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDUNKNOWN",
                "wrong-secret",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTokenMissingDbUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutDbUser = validToken.replaceFirst("DBUser=admin&", "");

        assertFalse(validator.validate(withoutDbUser, "admin"));
    }

    @Test
    void validateRejectsTokenForWrongUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "attacker"),
                "Token signed for 'admin' must be rejected when client connects as 'attacker'");
    }

    @Test
    void validateAcceptsTokenWhenClientUsernameIsNull() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, null),
                "Null clientUsername should skip the identity check (backwards compat)");
    }

    @Test
    void validateAcceptsTokenWithUrlEncodedDbUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        // Username with characters that require URL encoding exercises the
        // encoding path independently of the validator's decode logic
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "db+admin@example.com",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "db+admin@example.com"));
    }

    @Test
    void validateRejectsTokenWithWrongRegion() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        // Tampering with the region in the credential scope invalidates the signature
        String tamperedToken = token.replace("us-east-1", "eu-west-1");

        assertFalse(validator.validate(tamperedToken, "admin"));
    }

    @Test
    void validateRejectsTokenMissingSignatureParameter() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutSignature = validToken.replaceFirst("&X-Amz-Signature=[0-9a-f]+", "");

        assertFalse(validator.validate(withoutSignature, "admin"));
    }
}
