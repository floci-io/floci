package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::GatewayResponse}, backed by
 * {@link ApiGatewayService}.
 *
 * <p>A gateway response has no id of its own on the management plane: it is addressed by REST API
 * and response type, and {@code PutGatewayResponse} is an upsert. {@code Ref} and
 * {@code Fn::GetAtt Id} report {@code <restApiId>-<responseType>}, which is also what delete
 * needs. StatusCode, ResponseParameters and ResponseTemplates update in place through a fresh put;
 * RestApiId and ResponseType are createOnly, so a change to either is refused as a replacement
 * rather than silently customising a different type under the old id.
 */
@ApplicationScoped
public class ApiGatewayGatewayResponseCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ApiGatewayGatewayResponseCfnProvisioner.class);

    private static final String TYPE = "AWS::ApiGateway::GatewayResponse";
    private static final String NOT_FOUND = "NotFoundException";
    private static final String ID_SEPARATOR = "-";

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayGatewayResponseCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String restApiId = blankToNull(ctx.resolveOptional(props, "RestApiId"));
        String responseType = blankToNull(ctx.resolveOptional(props, "ResponseType"));
        if (restApiId == null || responseType == null) {
            throw new AwsException("ValidationError",
                    TYPE + " requires both RestApiId and ResponseType.", 400);
        }

        if (ctx.isUpdate()) {
            String[] prior = splitId(ctx.priorPhysicalId());
            if (prior != null) {
                rejectIfChanged("RestApiId", prior[0], restApiId);
                rejectIfChanged("ResponseType", prior[1], responseType);
            }
        }

        Map<String, Object> request = new HashMap<>();
        String statusCode = blankToNull(ctx.resolveOptional(props, "StatusCode"));
        if (statusCode != null) {
            request.put("statusCode", statusCode);
        }
        request.put("responseParameters", resolveStringMap(props, "ResponseParameters", ctx));
        request.put("responseTemplates", resolveStringMap(props, "ResponseTemplates", ctx));
        apiGatewayService.putGatewayResponse(ctx.region(), restApiId, responseType, request);
        record(r, restApiId, responseType);
    }

    private static void record(StackResource r, String restApiId, String responseType) {
        String id = restApiId + ID_SEPARATOR + responseType;
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
    }

    /**
     * {@code [restApiId, responseType]}, or null when the id is not in that shape. The response
     * type never contains a dash, so the last one is the separator whatever the API id looks like.
     */
    private static String[] splitId(String physicalId) {
        if (physicalId == null) {
            return null;
        }
        int separator = physicalId.lastIndexOf(ID_SEPARATOR);
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return null;
        }
        return new String[] {physicalId.substring(0, separator), physicalId.substring(separator + 1)};
    }

    private static Map<String, String> resolveStringMap(JsonNode props, String name, ProvisionContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return out;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved == null || !resolved.isObject()) {
            return out;
        }
        resolved.fields().forEachRemaining(field -> {
            String value = ctx.engine().resolve(field.getValue());
            if (value != null) {
                out.put(field.getKey(), value);
            }
        });
        return out;
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        if (!Objects.equals(blankToNull(existing), blankToNull(requested))) {
            throw new AwsException("ValidationError",
                    "Updating " + property + " requires resource replacement, which is not supported.", 400);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Without this the customisation outlives the stack and keeps shaping the API's errors. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        String[] parts = splitId(physicalId);
        if (parts == null) {
            LOG.warnv("Gateway response {0} has no <restApiId>-<responseType> physical id, leaving it in place",
                    physicalId);
            return;
        }
        CfnDeletes.safeDelete("gateway response", physicalId,
                () -> apiGatewayService.deleteGatewayResponse(region, parts[0], parts[1]), NOT_FOUND);
    }
}
