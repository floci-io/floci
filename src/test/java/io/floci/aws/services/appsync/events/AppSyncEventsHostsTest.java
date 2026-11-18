package io.floci.aws.services.appsync.events;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppSyncEventsHostsTest {

    @ParameterizedTest
    @CsvSource({
            "abc123.appsync-api.us-east-1.amazonaws.com,      appsync-api",
            "abc123.appsync-api.us-east-1.localhost:4566,     appsync-api",
            "ABC123.appsync-api.eu-west-1.localhost,          appsync-api",
            "abc123.appsync-api.localhost.floci.io:4566,      appsync-api",
            "abc123.appsync-realtime-api.us-east-1.localhost, appsync-realtime-api",
            "abc123.appsync-api.cn-north-1.amazonaws.com.cn,  appsync-api",
            "abc123.appsync-api.us-east-1.floci.internal,     appsync-api"})
    void anEventsHostNamesItsApi(String host, String label) {
        assertEquals("abc123", AppSyncEventsHosts.extractApiId(host, label, "floci.internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost:4566",
            "abc123.appsync-realtime-api.us-east-1.localhost",
            "abc123.execute-api.us-east-1.localhost",
            "abc123.appsync-api.not-a-region.localhost",
            "abc123.appsync-api.us-east-1.example.com"})
    void anyOtherHostIsNotAnEventsHttpHost(String host) {
        assertNull(AppSyncEventsHosts.extractApiId(host, AppSyncEventsHosts.HTTP_LABEL, "floci.internal"));
    }
}
