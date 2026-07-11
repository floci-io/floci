package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon MQ control plane (REST JSON). Paths and wire keys mirror the AWS
 * {@code mq} API (camelCase bodies under {@code /v1/brokers}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmazonMqController {

    private final AmazonMqService service;

    @Inject
    public AmazonMqController(AmazonMqService service) {
        this.service = service;
    }

    @POST
    @Path("/v1/brokers")
    public Response createBroker(Map<String, Object> request) {
        CreateBrokerParams params = new CreateBrokerParams(
                str(request, "brokerName"),
                str(request, "engineType"),
                str(request, "engineVersion"),
                str(request, "deploymentMode"),
                str(request, "hostInstanceType"),
                bool(request, "publiclyAccessible"),
                bool(request, "autoMinorVersionUpgrade"),
                parseUsers(request.get("users")),
                tags(request.get("tags")));
        Broker broker = service.createBroker(params);
        return Response.ok(Map.of(
                "brokerArn", broker.getBrokerArn(),
                "brokerId", broker.getBrokerId())).build();
    }

    @GET
    @Path("/v1/brokers")
    public Response listBrokers() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Broker b : service.listBrokers()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("brokerArn", b.getBrokerArn());
            summary.put("brokerId", b.getBrokerId());
            summary.put("brokerName", b.getBrokerName());
            summary.put("brokerState", b.getBrokerState());
            summary.put("created", b.getCreated());
            summary.put("deploymentMode", b.getDeploymentMode());
            summary.put("engineType", b.getEngineType());
            summary.put("hostInstanceType", b.getHostInstanceType());
            summaries.add(summary);
        }
        return Response.ok(Map.of("brokerSummaries", summaries)).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}")
    public Response describeBroker(@PathParam("broker-id") String brokerId) {
        return Response.ok(brokerResponse(service.describeBroker(brokerId))).build();
    }

    // Builds the DescribeBroker response explicitly. The Broker model persists
    // internal bookkeeping (containerId, accountId, volumeId) so the broker can be
    // managed after a restart, but those fields are not part of the AWS shape — hand-
    // building the response keeps them out of the client-facing payload.
    private static Map<String, Object> brokerResponse(Broker b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", b.getBrokerId());
        body.put("brokerArn", b.getBrokerArn());
        body.put("brokerName", b.getBrokerName());
        body.put("brokerState", b.getBrokerState());
        body.put("engineType", b.getEngineType());
        body.put("engineVersion", b.getEngineVersion());
        body.put("deploymentMode", b.getDeploymentMode());
        body.put("hostInstanceType", b.getHostInstanceType());
        body.put("publiclyAccessible", b.isPubliclyAccessible());
        body.put("autoMinorVersionUpgrade", b.isAutoMinorVersionUpgrade());
        body.put("created", b.getCreated());
        body.put("brokerInstances", b.getBrokerInstances());
        body.put("users", b.getUsers());
        body.put("tags", b.getTags());
        return body;
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}")
    public Response deleteBroker(@PathParam("broker-id") String brokerId) {
        service.deleteBroker(brokerId);
        return Response.ok(Map.of("brokerId", brokerId)).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/reboot")
    public Response rebootBroker(@PathParam("broker-id") String brokerId) {
        service.rebootBroker(brokerId);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response createUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username,
                               Map<String, Object> request) {
        MqUser user = new MqUser(
                username,
                str(request, "password"),
                bool(request, "consoleAccess"),
                strList(request.get("groups")));
        service.createUser(brokerId, user);
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response describeUser(@PathParam("broker-id") String brokerId,
                                 @PathParam("username") String username) {
        MqUser user = service.describeUser(brokerId, username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("username", user.getUsername());
        body.put("consoleAccess", user.isConsoleAccess());
        body.put("groups", user.getGroups());
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users")
    public Response listUsers(@PathParam("broker-id") String brokerId) {
        List<Map<String, Object>> users = new ArrayList<>();
        for (MqUser u : service.listUsers(brokerId)) {
            users.add(Map.of("username", u.getUsername()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("users", users);
        return Response.ok(body).build();
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response deleteUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username) {
        service.deleteUser(brokerId, username);
        return Response.ok(Map.of()).build();
    }

    // --- request parsing helpers ---

    private static String str(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> request, String key) {
        return Boolean.TRUE.equals(request.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tags(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<MqUser> parseUsers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<MqUser> users = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> u = (Map<String, Object>) map;
                users.add(new MqUser(
                        str(u, "username"),
                        str(u, "password"),
                        bool(u, "consoleAccess"),
                        strList(u.get("groups"))));
            }
        }
        return users;
    }
}
