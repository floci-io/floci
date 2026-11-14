package io.floci.aws.testing;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ec2.Ec2BlockPublicAccessService;
import io.floci.aws.services.ec2.Ec2EbsEncryptionService;
import io.floci.aws.services.ec2.Ec2IpamService;
import io.floci.aws.services.ec2.Ec2QueryHandler;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.FlowLogService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * EC2 Query requests sent straight to an {@link Ec2QueryHandler} in {@link #REGION}, for unit tests
 * that check what a call answers without HTTP. The handler's other services are mocks.
 */
public final class Ec2QueryHandlerFixtures {

    public static final String REGION = "us-east-1";

    private Ec2QueryHandlerFixtures() {
    }

    /** A handler over {@code service} for {@link #REGION} and {@link Ec2ServiceFixtures#ACCOUNT}. */
    public static Ec2QueryHandler queryHandler(Ec2Service service) {
        return new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2BlockPublicAccessService.class),
                mock(Ec2IpamService.class), new RegionResolver(REGION, Ec2ServiceFixtures.ACCOUNT));
    }

    /** Sends {@code action} with {@code params}, given as name and value pairs, and returns the answer. */
    public static Response send(Ec2QueryHandler handler, String action, String... params) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            form.add(params[i], params[i + 1]);
        }
        return handler.handle(action, form, REGION);
    }

    /** As {@link #send}, asserting that the call succeeds, and returns the response body. */
    public static String ok(Ec2QueryHandler handler, String action, String... params) {
        Response response = send(handler, action, params);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }
}
