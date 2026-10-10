package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

/**
 * Legacy Amazon Elasticsearch Service API ({@code 2015-01-01}), still used by
 * {@code aws es} and Terraform's {@code aws_elasticsearch_domain}. It addresses
 * the same domains as the OpenSearch API, so every operation delegates to
 * {@link OpenSearchController} and only renames the members the legacy model
 * spells differently: {@code ElasticsearchVersion} / {@code EngineVersion} and
 * {@code ElasticsearchClusterConfig} / {@code ClusterConfig}. The legacy version
 * is bare ({@code 7.10}) where the store holds {@code Elasticsearch_7.10}.
 */
@Path("/2015-01-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ElasticsearchController {

    private static final String ES_PREFIX = "Elasticsearch_";
    /** The legacy API always creates an Elasticsearch domain; 7.10 is the newest Floci serves. */
    private static final String DEFAULT_VERSION = ES_PREFIX + "7.10";

    private final OpenSearchController delegate;
    private final ObjectMapper objectMapper;

    @Inject
    public ElasticsearchController(OpenSearchService service, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.delegate = new OpenSearchController(service, regionResolver, objectMapper);
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/es/domain")
    public Response createElasticsearchDomain(@Context HttpHeaders headers, String body) {
        return toLegacy(delegate.createDomain(headers, toOpenSearch(body, true)));
    }

    @GET
    @Path("/es/domain/{domainName}")
    public Response describeElasticsearchDomain(@Context HttpHeaders headers,
                                                @PathParam("domainName") String domainName) {
        return toLegacy(delegate.describeDomain(headers, domainName));
    }

    @POST
    @Path("/es/domain-info")
    public Response describeElasticsearchDomains(@Context HttpHeaders headers, String body) {
        return toLegacy(delegate.describeDomains(headers, body));
    }

    @GET
    @Path("/domain")
    public Response listDomainNames(@Context HttpHeaders headers,
                                    @QueryParam("engineType") String engineType) {
        return delegate.listDomainNames(headers, engineType);
    }

    @GET
    @Path("/es/domain/{domainName}/config")
    public Response describeElasticsearchDomainConfig(@Context HttpHeaders headers,
                                                      @PathParam("domainName") String domainName) {
        return toLegacy(delegate.describeDomainConfig(headers, domainName));
    }

    @POST
    @Path("/es/domain/{domainName}/config")
    public Response updateElasticsearchDomainConfig(@Context HttpHeaders headers,
                                                    @PathParam("domainName") String domainName,
                                                    String body) {
        return toLegacy(delegate.updateDomainConfig(headers, domainName, toOpenSearch(body, false)));
    }

    @DELETE
    @Path("/es/domain/{domainName}")
    public Response deleteElasticsearchDomain(@Context HttpHeaders headers,
                                              @PathParam("domainName") String domainName) {
        return toLegacy(delegate.deleteDomain(headers, domainName));
    }

    @POST
    @Path("/tags")
    public Response addTags(@Context HttpHeaders headers, String body) {
        return delegate.addTags(headers, body);
    }

    @GET
    @Path("/tags/")
    public Response listTags(@Context HttpHeaders headers, @QueryParam("arn") String arn) {
        return delegate.listTags(headers, arn);
    }

    @POST
    @Path("/tags-removal")
    public Response removeTags(@Context HttpHeaders headers, String body) {
        return delegate.removeTags(headers, body);
    }

    // ── Member translation ───────────────────────────────────────────────────

    private String toOpenSearch(String body, boolean create) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
        if (!(tree instanceof ObjectNode req)) {
            return body;
        }
        rename(req, "ElasticsearchClusterConfig", "ClusterConfig");
        JsonNode version = req.remove("ElasticsearchVersion");
        if (version != null && !version.isNull()) {
            req.put("EngineVersion", toEngineVersion(version.asText()));
        } else if (create) {
            req.put("EngineVersion", DEFAULT_VERSION);
        }
        return req.toString();
    }

    private static Response toLegacy(Response response) {
        if (!(response.getEntity() instanceof ObjectNode body)) {
            return response;
        }
        if (body.get("DomainStatus") instanceof ObjectNode status) {
            toLegacyStatus(status);
        }
        body.path("DomainStatusList").forEach(s -> toLegacyStatus((ObjectNode) s));
        if (body.get("DomainConfig") instanceof ObjectNode config) {
            rename(config, "ClusterConfig", "ElasticsearchClusterConfig");
            if (config.remove("EngineVersion") instanceof ObjectNode version) {
                version.put("Options", toLegacyVersion(version.path("Options").asText(null)));
                config.set("ElasticsearchVersion", version);
            }
        }
        return response;
    }

    private static void toLegacyStatus(ObjectNode status) {
        rename(status, "ClusterConfig", "ElasticsearchClusterConfig");
        JsonNode version = status.remove("EngineVersion");
        if (version != null) {
            status.put("ElasticsearchVersion", toLegacyVersion(version.asText(null)));
        }
    }

    private static void rename(ObjectNode node, String from, String to) {
        JsonNode value = node.remove(from);
        if (value != null) {
            node.set(to, value);
        }
    }

    static String toEngineVersion(String legacy) {
        if (legacy == null || legacy.isBlank()
                || legacy.startsWith(ES_PREFIX) || legacy.startsWith("OpenSearch_")) {
            return legacy;
        }
        return ES_PREFIX + legacy;
    }

    static String toLegacyVersion(String engineVersion) {
        return engineVersion != null && engineVersion.startsWith(ES_PREFIX)
                ? engineVersion.substring(ES_PREFIX.length())
                : engineVersion;
    }
}
