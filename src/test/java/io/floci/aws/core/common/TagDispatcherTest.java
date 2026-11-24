package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.ParallelLocks;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class TagDispatcherTest {

    private static final String GROUP = "arn:aws:scheduler:us-east-1:000000000000:schedule-group/reports";
    private static final String REST_API = "arn:aws:apigateway:us-east-1::/restapis/abc123";

    private final TagHandler scheduler = handler("scheduler");
    private final TagHandler apiGateway = handler("apigateway");
    private final TagDispatcher dispatcher = new TagDispatcher(List.of(scheduler, apiGateway),
            new RegionResolver("us-east-1", "000000000000"), new ObjectMapper());

    /**
     * Scheduler, which the generated table reads whole, tags on its modeled /tags/{ResourceArn} alone, the ARN sent
     * as one encoded segment: the bare /tags, or an ARN whose slash is not encoded, is no route of its model and is
     * refused as an ARN that path does not serve.
     */
    @Test
    void aServiceTheTableReadsIsServedOnlyOnTheRoutesItsModelGives() {
        String encoded = "/tags/arn%3Aaws%3Ascheduler%3Aus-east-1%3A000000000000%3Aschedule-group%2Freports";
        assertSame(scheduler, dispatcher.resolveHandler(GROUP, sent(encoded)));
        for (String refused : List.of("/tags", "/tags/" + GROUP)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> dispatcher.resolveHandler(GROUP, sent(refused)));
            assertEquals(400, error.getHttpStatus());
            assertEquals("Invalid resource ARN: " + GROUP, error.getMessage());
        }
    }

    /**
     * API Gateway is still built by hand, its tagged ARN read wherever the controller reads it, so every route serves
     * it.
     */
    @Test
    void aServiceBuiltByHandIsServedOnEveryRoute() {
        assertSame(apiGateway, dispatcher.resolveHandler(REST_API, sent("/tags")));
        assertSame(apiGateway, dispatcher.resolveHandler(REST_API, sent("/tags/" + REST_API)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "{}", "null"})
    void strictTaggingRejectsTrailingTokensBeforeCallingTheService(String trailing) {
        when(scheduler.strictTagValidation()).thenReturn(true);
        when(scheduler.tagsBodyKey()).thenReturn("tags");
        when(scheduler.tagValidationErrorCode()).thenReturn("ValidationException");
        HttpHeaders headers = mock(HttpHeaders.class);
        try (Response success = Response.ok().build()) {
            AwsException error = assertThrows(AwsException.class,
                    () -> dispatcher.tagResourcePost(headers, GROUP, "{\"tags\":{\"team\":\"ops\"}} " + trailing, success));
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            verify(scheduler, never()).tagResource(any(), any(), anyMap());
        }
    }

    @Test
    void strictParsingDoesNotChangeTheInjectedMapperOrLenientHandlers() {
        when(scheduler.strictTagValidation()).thenReturn(true);
        when(scheduler.tagsBodyKey()).thenReturn("tags");
        when(apiGateway.tagsBodyKey()).thenReturn("tags");
        HttpHeaders headers = mock(HttpHeaders.class);
        try (Response success = Response.ok().build()) {
            String body = "{\"tags\":{\"team\":\"ops\"}}";
            assertSame(success, dispatcher.tagResourcePost(headers, GROUP, body, success));
            assertSame(success, dispatcher.tagResourcePost(headers, REST_API, body + " {}", success));
            verify(scheduler).tagResource("us-east-1", GROUP, Map.of("team", "ops"));
            verify(apiGateway).tagResource("us-east-1", REST_API, Map.of("team", "ops"));
        }
    }

    private static TagHandler handler(String serviceKey) {
        TagHandler handler = mock(TagHandler.class);
        when(handler.serviceKey()).thenReturn(serviceKey);
        return handler;
    }

    /** The URI of a request sent to the path, as {@link UriInfo} gives it. */
    private static UriInfo sent(String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + path));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }
}
