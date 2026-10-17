package io.github.hectorvent.floci.services.lambdaweb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaWebDataPlaneTest {

    @Test
    void onlyTheMintedDomainShapesAreClaimed() {
        assertTrue(LambdaWebDataPlane.isLambdaWebHost("abcdefghijklmnop.001.lambda-web.us-east-1.localhost:4566"));
        assertTrue(LambdaWebDataPlane.isLambdaWebHost("abcdefghijklmnop.001.lambda-web.us-east-1.localhost"));
        assertTrue(LambdaWebDataPlane.isLambdaWebHost("abcdefghijklmnop.lambda-web.global.localhost:4566"));
        // A near miss is the AWS 404, so the case and the port are claimed too.
        assertTrue(LambdaWebDataPlane.isLambdaWebHost("ABCDEFGHIJKLMNOP.001.LAMBDA-WEB.US-EAST-1.LOCALHOST:4566"));
        // A dotted S3 bucket or another service's host is not.
        assertFalse(LambdaWebDataPlane.isLambdaWebHost("my.lambda-web.archive.localhost:4566"));
        assertFalse(LambdaWebDataPlane.isLambdaWebHost("abcdefghijklmnop.lambda-url.us-east-1.localhost:4566"));
        assertFalse(LambdaWebDataPlane.isLambdaWebHost("localhost:4566"));
    }

    @Test
    void requestIdsAre26CharactersOfBase32() {
        String id = LambdaWebDataPlane.newRequestId();
        assertTrue(id.matches("[A-Z2-7]{26}"), id);
    }

    @Test
    void thePortComesFromTheHostOrTheScheme() {
        assertEquals(4566, LambdaWebDataPlane.port("x.001.lambda-web.us-east-1.localhost:4566", "http"));
        assertEquals(80, LambdaWebDataPlane.port("x.001.lambda-web.us-east-1.localhost", "http"));
        assertEquals(443, LambdaWebDataPlane.port("x.001.lambda-web.us-east-1.floci.example", "https"));
    }
}
