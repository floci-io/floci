package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.AuthorizerConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateSummary;
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

/**
 * REST-JSON routes for AWS IoT domain configurations: CreateDomainConfiguration,
 * DescribeDomainConfiguration, UpdateDomainConfiguration, DeleteDomainConfiguration and
 * ListDomainConfigurations, on the paths and shapes the AWS SDKs use.
 */
@Path("/domainConfigurations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotDomainConfigurationController {

    private final IotDomainConfigurationService domainConfigurationService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotDomainConfigurationController(IotDomainConfigurationService domainConfigurationService,
                                            RegionResolver regionResolver,
                                            ObjectMapper objectMapper) {
        this.domainConfigurationService = domainConfigurationService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/{domainConfigurationName}")
    public Response createDomainConfiguration(@Context HttpHeaders headers,
                                              @PathParam("domainConfigurationName") String domainConfigurationName,
                                              String body) {
        IotDomainConfiguration created = domainConfigurationService.createDomainConfiguration(
                domainConfigurationName, readJson(body), regionResolver.resolveRegion(headers));
        return Response.ok(nameAndArn(created)).build();
    }

    @GET
    @Path("/{domainConfigurationName}")
    public Response describeDomainConfiguration(@Context HttpHeaders headers,
                                                @PathParam("domainConfigurationName") String domainConfigurationName) {
        IotDomainConfiguration configuration = domainConfigurationService.describeDomainConfiguration(
                domainConfigurationName, regionResolver.resolveRegion(headers));
        return Response.ok(describeResponse(configuration)).build();
    }

    @PUT
    @Path("/{domainConfigurationName}")
    public Response updateDomainConfiguration(@Context HttpHeaders headers,
                                              @PathParam("domainConfigurationName") String domainConfigurationName,
                                              String body) {
        IotDomainConfiguration updated = domainConfigurationService.updateDomainConfiguration(
                domainConfigurationName, readJson(body), regionResolver.resolveRegion(headers));
        return Response.ok(nameAndArn(updated)).build();
    }

    @DELETE
    @Path("/{domainConfigurationName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDomainConfiguration(@Context HttpHeaders headers,
                                              @PathParam("domainConfigurationName") String domainConfigurationName) {
        domainConfigurationService.deleteDomainConfiguration(domainConfigurationName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    public Response listDomainConfigurations(@Context HttpHeaders headers,
                                             @QueryParam("marker") String marker,
                                             @QueryParam("pageSize") Integer pageSize,
                                             @QueryParam("serviceType") String serviceType) {
        IotService.Page<IotDomainConfiguration> page = domainConfigurationService.listDomainConfigurations(
                regionResolver.resolveRegion(headers), serviceType, marker, pageSize);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("domainConfigurations");
        for (IotDomainConfiguration configuration : page.items()) {
            ObjectNode summary = summaries.addObject();
            summary.put("domainConfigurationName", configuration.getDomainConfigurationName());
            summary.put("domainConfigurationArn", configuration.getDomainConfigurationArn());
            summary.put("serviceType", configuration.getServiceType());
        }
        if (page.nextToken() != null) {
            response.put("nextMarker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode nameAndArn(IotDomainConfiguration configuration) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("domainConfigurationName", configuration.getDomainConfigurationName());
        response.put("domainConfigurationArn", configuration.getDomainConfigurationArn());
        return response;
    }

    private ObjectNode describeResponse(IotDomainConfiguration configuration) {
        ObjectNode response = nameAndArn(configuration);
        putIfPresent(response, "domainName", configuration.getDomainName());
        ArrayNode certificates = response.putArray("serverCertificates");
        for (ServerCertificateSummary certificate : configuration.getServerCertificates()) {
            ObjectNode summary = certificates.addObject();
            summary.put("serverCertificateArn", certificate.serverCertificateArn());
            summary.put("serverCertificateStatus", certificate.serverCertificateStatus());
            putIfPresent(summary, "serverCertificateStatusDetail", certificate.serverCertificateStatusDetail());
        }
        AuthorizerConfig authorizerConfig = configuration.getAuthorizerConfig();
        if (authorizerConfig != null) {
            ObjectNode authorizer = response.putObject("authorizerConfig");
            putIfPresent(authorizer, "defaultAuthorizerName", authorizerConfig.defaultAuthorizerName());
            if (authorizerConfig.allowAuthorizerOverride() != null) {
                authorizer.put("allowAuthorizerOverride", authorizerConfig.allowAuthorizerOverride());
            }
        }
        // Always present: SDK enums read an omitted status as an empty value, not as ENABLED.
        response.put("domainConfigurationStatus", configuration.getDomainConfigurationStatus());
        response.put("serviceType", configuration.getServiceType());
        response.put("domainType", configuration.getDomainType());
        if (configuration.getLastStatusChangeDate() != null) {
            response.put("lastStatusChangeDate", configuration.getLastStatusChangeDate().toEpochMilli() / 1000.0);
        }
        if (configuration.getTlsConfig() != null) {
            putIfPresent(response.putObject("tlsConfig"), "securityPolicy", configuration.getTlsConfig().securityPolicy());
        }
        ServerCertificateConfig certificateConfig = configuration.getServerCertificateConfig();
        if (certificateConfig != null) {
            ObjectNode node = response.putObject("serverCertificateConfig");
            if (certificateConfig.enableOCSPCheck() != null) {
                node.put("enableOCSPCheck", certificateConfig.enableOCSPCheck());
            }
            putIfPresent(node, "ocspLambdaArn", certificateConfig.ocspLambdaArn());
            putIfPresent(node, "ocspAuthorizedResponderArn", certificateConfig.ocspAuthorizedResponderArn());
        }
        putIfPresent(response, "authenticationType", configuration.getAuthenticationType());
        putIfPresent(response, "applicationProtocol", configuration.getApplicationProtocol());
        if (configuration.getClientCertificateConfig() != null) {
            putIfPresent(response.putObject("clientCertificateConfig"), "clientCertificateCallbackArn",
                    configuration.getClientCertificateConfig().clientCertificateCallbackArn());
        }
        return response;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }
}
