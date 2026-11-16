package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TagDispatcherTest {

    private static final String GROUP = "arn:aws:scheduler:us-east-1:000000000000:schedule-group/reports";
    private static final String PIPE = "arn:aws:pipes:us-east-1:000000000000:pipe/orders";

    private final TagHandler scheduler = handler("scheduler");
    private final TagHandler pipes = handler("pipes");
    private final TagDispatcher dispatcher = new TagDispatcher(List.of(scheduler, pipes),
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

    /** Pipes is still built by hand, its tagged ARN read wherever the controller reads it, so every route serves it. */
    @Test
    void aServiceBuiltByHandIsServedOnEveryRoute() {
        assertSame(pipes, dispatcher.resolveHandler(PIPE, sent("/tags")));
        assertSame(pipes, dispatcher.resolveHandler(PIPE, sent("/tags/" + PIPE)));
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
