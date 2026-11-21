package io.floci.aws.services.cloudtrail;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code sourceIPAddress} is "the IP address that the request was made from"
 * (https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-event-reference-record-contents.html):
 * the connection's, not an {@code X-Forwarded-For} any client can send.
 */
@QuarkusTest
class CloudTrailSourceAddressIntegrationTest {

    @Inject
    CloudTrailLogWriter writer;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClientSentForwardedForIsNotTheSourceAddress() throws IOException {
        assertThat(CloudTrailSourceAddress.recordedFor(writer, "203.0.113.7"), equalTo("127.0.0.1"));
    }
}
