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

        assertTrue(validator.validate(token));
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

        assertFalse(validator.validate(brokenToken));
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

        assertFalse(validator.validate(token));
    }
}
