package io.github.hectorvent.floci.services.oam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.oam.model.OamLink;
import io.github.hectorvent.floci.services.oam.model.OamSink;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OamController {
    private final OamService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OamController(OamService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST @Path("/CreateSink")
    public Response createSink(@Context HttpHeaders headers, Map<String, Object> request) {
        OamSink sink = service.createSink(string(request, "Name"), stringMap(request.get("Tags")), region(headers));
        return Response.ok(sinkBody(sink, true)).build();
    }

    @POST @Path("/GetSink")
    public Response getSink(Map<String, Object> request) {
        OamSink sink = service.getSink(string(request, "Identifier"));
        return Response.ok(sinkBody(sink, boolDefaultTrue(request, "IncludeTags"))).build();
    }

    @POST @Path("/ListSinks")
    public Response listSinks(@Context HttpHeaders headers, Map<String, Object> request) {
        int max = maxResults(request, 100, 100);
        int start = OamService.decodeToken(string(request, "NextToken"));
        List<OamSink> all = service.listSinks(region(headers));
        int end = Math.min(start + max, all.size());
        List<Map<String, Object>> items = all.subList(Math.min(start, all.size()), end).stream()
                .map(s -> Map.<String, Object>of("Arn", s.getArn(), "Id", s.getId(), "Name", s.getName())).toList();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("Items", items);
        if (end < all.size()) out.put("NextToken", OamService.encodeToken(end));
        return Response.ok(out).build();
    }

    @POST @Path("/PutSinkPolicy")
    public Response putSinkPolicy(Map<String, Object> request) {
        String arn = string(request, "SinkIdentifier");
        String policy = string(request, "Policy");
        service.putSinkPolicy(arn, policy);
        OamSink sink = service.getSink(arn);
        return Response.ok(Map.of("Policy", policy, "SinkArn", sink.getArn(), "SinkId", sink.getId())).build();
    }

    @POST @Path("/GetSinkPolicy")
    public Response getSinkPolicy(Map<String, Object> request) {
        OamSink sink = service.getSink(string(request, "SinkIdentifier"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (sink.getPolicy() != null) out.put("Policy", sink.getPolicy());
        out.put("SinkArn", sink.getArn()); out.put("SinkId", sink.getId());
        return Response.ok(out).build();
    }

    @POST @Path("/DeleteSink")
    public Response deleteSink(Map<String, Object> request) {
        service.deleteSink(string(request, "Identifier"));
        return Response.ok(Map.of()).build();
    }

    @POST @Path("/CreateLink")
    public Response createLink(@Context HttpHeaders headers, Map<String, Object> request) {
        OamLink link = service.createLink(string(request, "SinkIdentifier"), string(request, "LabelTemplate"),
                stringList(request.get("ResourceTypes")), objectMap(request.get("LinkConfiguration")),
                stringMap(request.get("Tags")), region(headers));
        return Response.ok(linkBody(link, true)).build();
    }

    @POST @Path("/GetLink")
    public Response getLink(Map<String, Object> request) {
        return Response.ok(linkBody(service.getLink(string(request, "Identifier")), boolDefaultTrue(request, "IncludeTags"))).build();
    }

    @POST @Path("/ListLinks")
    public Response listLinks(@Context HttpHeaders headers, Map<String, Object> request) {
        int max = maxResults(request, 5, 5);
        int start = OamService.decodeToken(string(request, "NextToken"));
        List<OamLink> all = service.listLinks(region(headers));
        int safeStart = Math.min(start, all.size()); int end = Math.min(safeStart + max, all.size());
        List<Map<String, Object>> items = all.subList(safeStart, end).stream().map(this::linkSummary).toList();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("Items", items);
        if (end < all.size()) out.put("NextToken", OamService.encodeToken(end));
        return Response.ok(out).build();
    }

    @POST @Path("/ListAttachedLinks")
    public Response listAttachedLinks(Map<String, Object> request) {
        int max = maxResults(request, 100, 1000);
        int start = OamService.decodeToken(string(request, "NextToken"));
        List<OamLink> all = service.listAttachedLinks(string(request, "SinkIdentifier"));
        int safeStart = Math.min(start, all.size()); int end = Math.min(safeStart + max, all.size());
        List<Map<String, Object>> items = all.subList(safeStart, end).stream().map(link -> Map.<String, Object>of(
                "Label", link.getLabel(), "LinkArn", link.getArn(), "ResourceTypes", link.getResourceTypes())).toList();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("Items", items);
        if (end < all.size()) out.put("NextToken", OamService.encodeToken(end));
        return Response.ok(out).build();
    }

    @POST @Path("/UpdateLink")
    public Response updateLink(Map<String, Object> request) {
        OamLink link = service.updateLink(string(request, "Identifier"), stringList(request.get("ResourceTypes")),
                objectMap(request.get("LinkConfiguration")));
        return Response.ok(linkBody(link, boolDefaultTrue(request, "IncludeTags"))).build();
    }

    @POST @Path("/DeleteLink")
    public Response deleteLink(Map<String, Object> request) {
        service.deleteLink(string(request, "Identifier"));
        return Response.ok(Map.of()).build();
    }

    @GET @Path("/tags/{resourceArn:.+}")
    public Response listTags(@PathParam("resourceArn") String resourceArn) {
        return Response.ok(Map.of("Tags", service.tags(decode(resourceArn)))).build();
    }

    @PUT @Path("/tags/{resourceArn:.+}")
    public Response tag(@PathParam("resourceArn") String resourceArn, Map<String, Object> request) {
        service.tag(decode(resourceArn), stringMap(request.get("Tags")));
        return Response.ok(Map.of()).build();
    }

    @DELETE @Path("/tags/{resourceArn:.+}")
    public Response untag(@PathParam("resourceArn") String resourceArn, @QueryParam("tagKeys") List<String> tagKeys) {
        service.untag(decode(resourceArn), tagKeys);
        return Response.ok(Map.of()).build();
    }

    private Map<String, Object> sinkBody(OamSink sink, boolean includeTags) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Arn", sink.getArn()); out.put("Id", sink.getId()); out.put("Name", sink.getName());
        if (includeTags) out.put("Tags", sink.getTags());
        return out;
    }

    private Map<String, Object> linkBody(OamLink link, boolean includeTags) {
        Map<String, Object> out = linkSummary(link);
        out.put("LabelTemplate", link.getLabelTemplate());
        if (!link.getLinkConfiguration().isEmpty()) out.put("LinkConfiguration", link.getLinkConfiguration());
        if (includeTags) out.put("Tags", link.getTags());
        return out;
    }

    private Map<String, Object> linkSummary(OamLink link) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Arn", link.getArn()); out.put("Id", link.getId()); out.put("Label", link.getLabel());
        out.put("ResourceTypes", link.getResourceTypes()); out.put("SinkArn", link.getSinkArn());
        return out;
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static String string(Map<String, Object> request, String key) { Object v = request == null ? null : request.get(key); return v == null ? null : String.valueOf(v); }
    private static boolean boolDefaultTrue(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null || (value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)));
    }
    private static int maxResults(Map<String, Object> request, int defaultValue, int max) {
        Object value = request == null ? null : request.get("MaxResults");
        if (value == null) return defaultValue;
        int parsed;
        try { parsed = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { throw new AwsException("InvalidParameterException", "MaxResults is invalid.", 400); }
        if (parsed < 1 || parsed > max) throw new AwsException("InvalidParameterException", "MaxResults must be between 1 and " + max + ".", 400);
        return parsed;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectMap(Object value) { return value instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null; }
    @SuppressWarnings("unchecked") private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, String> out = new LinkedHashMap<>(); map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v))); return out;
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        return list.stream().map(String::valueOf).toList();
    }
}
