package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS IoT Core data-plane REST endpoints: endpoint discovery ({@code DescribeEndpoint}) and the
 * HTTP {@code Publish} action ({@code POST /topics/{topicName}}), which injects a message into the
 * embedded MQTT broker.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class IotController {

    private final IotMqttBroker broker;
    private final IotBrokerEndpoint brokerEndpoint;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotController(IotMqttBroker broker, IotBrokerEndpoint brokerEndpoint,
                         RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.broker = broker;
        this.brokerEndpoint = brokerEndpoint;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/endpoint")
    public Response describeEndpoint(@Context HttpHeaders headers,
                                     @QueryParam("endpointType") String endpointType) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("endpointAddress", resolveEndpointAddress(endpointType, region));
        return Response.ok(resp).build();
    }

    @POST
    @Path("/topics/{topic: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response publish(@PathParam("topic") String topic,
                            @QueryParam("qos") Integer qos,
                            byte[] body) {
        broker.publish(topic, body, qos == null ? 0 : qos);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String resolveEndpointAddress(String endpointType, String region) {
        String type = (endpointType == null || endpointType.isBlank()) ? "iot:Data-ATS" : endpointType;
        // Data-plane endpoints point at the running local MQTT broker so clients can connect.
        if (brokerEndpoint.isRunning() && (type.equals("iot:Data-ATS") || type.equals("iot:Data"))) {
            int port = brokerEndpoint.tlsEnabled() ? brokerEndpoint.wssPort() : brokerEndpoint.wsPort();
            return brokerEndpoint.advertisedHost() + ":" + port;
        }
        String prefix = endpointPrefix();
        return switch (type) {
            case "iot:Data" -> prefix + ".iot." + region + ".amazonaws.com";
            case "iot:CredentialProvider" -> prefix + ".credentials.iot." + region + ".amazonaws.com";
            case "iot:Jobs" -> prefix + ".jobs.iot." + region + ".amazonaws.com";
            default -> prefix + "-ats.iot." + region + ".amazonaws.com";
        };
    }

    private String endpointPrefix() {
        String digits = regionResolver.getAccountId().replaceAll("\\D", "");
        if (digits.length() < 12) {
            digits = (digits + "000000000000").substring(0, 12);
        }
        return "a" + digits.substring(0, 11);
    }
}
