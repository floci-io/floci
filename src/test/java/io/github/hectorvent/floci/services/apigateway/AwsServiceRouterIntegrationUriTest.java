package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parsing of an AWS integration URI, which is the ARN a customer writes into their integration
 * config: {@code arn:<partition>:apigateway:<region>:<service>:action/<Action>}. The parse needs
 * none of the router's handlers, so they are left null.
 */
class AwsServiceRouterIntegrationUriTest {

    private final AwsServiceRouter router = new AwsServiceRouter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void parsesAnActionUri() {
        AwsServiceRouter.IntegrationTarget target =
                router.parseIntegrationUri("arn:aws:apigateway:us-east-1:sqs:action/SendMessage");

        assertEquals("us-east-1", target.region());
        assertEquals("sqs", target.service());
        assertEquals("SendMessage", target.action());
        assertNull(target.path());
    }

    @Test
    void parsesAPathUri() {
        AwsServiceRouter.IntegrationTarget target = router.parseIntegrationUri(
                "arn:aws:apigateway:us-east-1:s3:path/my-bucket/my-key");

        assertEquals("s3", target.service());
        assertNull(target.action());
        assertEquals("my-bucket/my-key", target.path());
    }

    /**
     * The guard was a literal {@code startsWith("arn:aws:apigateway:")}, so a customer running
     * against GovCloud or China wrote a legal integration URI and the router returned null, which
     * reads downstream as "this is not an AWS integration" rather than as an error.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws-us-gov:apigateway:us-gov-west-1:sqs:action/SendMessage",
            "arn:aws-cn:apigateway:cn-north-1:sqs:action/SendMessage",
            "arn:aws-iso-b:apigateway:us-isob-east-1:sqs:action/SendMessage"})
    void parsesAnActionUriFromAnyPartition(String uri) {
        AwsServiceRouter.IntegrationTarget target = router.parseIntegrationUri(uri);

        assertNotNull(target, uri + " was not recognised as an AWS integration URI");
        assertEquals("sqs", target.service());
        assertEquals("SendMessage", target.action());
    }

    /** A path segment may itself contain a colon, and the remainder is re-joined to keep it. */
    @Test
    void keepsColonsInsideAPath() {
        AwsServiceRouter.IntegrationTarget target = router.parseIntegrationUri(
                "arn:aws:apigateway:us-east-1:s3:path/bucket/a:b");

        assertEquals("bucket/a:b", target.path());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:lambda:us-east-1:000000000000:function:f",
            "arn:aws:apigateway:us-east-1:sqs",
            "http://example.com",
            "arn:aws:apigateway:us-east-1:sqs:unknown/Thing"})
    void rejectsWhatIsNotAnAwsIntegrationUri(String uri) {
        assertNull(router.parseIntegrationUri(uri));
    }

    @Test
    void rejectsNull() {
        assertNull(router.parseIntegrationUri(null));
    }
}
