package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A VPC endpoint survives the persistent storage modes. */
class OpenSearchVpcEndpointJacksonRoundTripTest {

    /** Same configuration {@code PersistentStorage}, {@code HybridStorage} and {@code WalStorage} use. */
    private final ObjectMapper mapper = new ObjectMapper();

    OpenSearchVpcEndpointJacksonRoundTripTest() {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void anEndpointSurvivesARoundTrip() throws Exception {
        OpenSearchVpcEndpoint endpoint = new OpenSearchVpcEndpoint();
        endpoint.setVpcEndpointId("aos-0123456789ab");
        endpoint.setVpcEndpointOwner("000000000000");
        endpoint.setDomainArn("arn:aws:es:us-east-1:000000000000:domain/orders");
        VpcOptions options = new VpcOptions();
        options.setSubnetIds(List.of("subnet-a", "subnet-b"));
        options.setSecurityGroupIds(List.of("sg-one"));
        options.setAvailabilityZones(List.of("us-east-1a", "us-east-1b"));
        options.setVpcId("vpc-one");
        endpoint.setVpcOptions(options);
        endpoint.setEgressEnabled(true);
        endpoint.setStatus("ACTIVE");
        endpoint.setClientToken("token-1");

        OpenSearchVpcEndpoint reloaded =
                mapper.readValue(mapper.writeValueAsString(endpoint), OpenSearchVpcEndpoint.class);

        assertEquals("aos-0123456789ab", reloaded.getVpcEndpointId());
        assertEquals("000000000000", reloaded.getVpcEndpointOwner());
        assertEquals("arn:aws:es:us-east-1:000000000000:domain/orders", reloaded.getDomainArn());
        assertEquals(List.of("subnet-a", "subnet-b"), reloaded.getVpcOptions().getSubnetIds());
        assertEquals(List.of("sg-one"), reloaded.getVpcOptions().getSecurityGroupIds());
        assertEquals(List.of("us-east-1a", "us-east-1b"), reloaded.getVpcOptions().getAvailabilityZones());
        assertEquals("vpc-one", reloaded.getVpcOptions().getVpcId());
        assertTrue(reloaded.getEgressEnabled());
        assertEquals("ACTIVE", reloaded.getStatus());
        assertEquals("token-1", reloaded.getClientToken());
    }
}
