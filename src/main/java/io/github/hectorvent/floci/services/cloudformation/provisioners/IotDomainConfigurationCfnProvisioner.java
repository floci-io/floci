package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotDomainConfigurationService;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateSummary;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions {@code AWS::IoT::DomainConfiguration}. The physical id is the configuration name and
 * {@code Fn::GetAtt} exposes {@code Arn}, {@code DomainType} and {@code ServerCertificates}; the
 * last is a list of objects on AWS, carried here as a JSON string because attributes are strings.
 *
 * <p>Follows the CloudFormation handler: a configuration is created ENABLED and a
 * {@code DomainConfigurationStatus} of DISABLED is applied with a follow-up update, tags go
 * through TagResource, and a delete disables first because the API refuses to delete an ENABLED
 * configuration.
 */
@ApplicationScoped
public class IotDomainConfigurationCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IotDomainConfigurationCfnProvisioner.class);
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final int NAME_MAX_LENGTH = 128;
    /** Template properties that are not part of the create request body. */
    private static final Set<String> OUTSIDE_THE_REQUEST =
            Set.of("DomainConfigurationName", "DomainConfigurationStatus", "Tags");
    /** createOnly in the registry schema: a change to any of these replaces the configuration. */
    private static final Set<String> CREATE_ONLY =
            Set.of("domainName", "serviceType", "validationCertificateArn", "serverCertificateArns");

    private final IotDomainConfigurationService domainConfigurationService;

    public IotDomainConfigurationCfnProvisioner(IotDomainConfigurationService domainConfigurationService) {
        this.domainConfigurationService = domainConfigurationService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::IoT::DomainConfiguration");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicitName = ctx.resolveOptional(props, "DomainConfigurationName");
        String name = ctx.stablePhysicalName(explicitName, r.getLogicalId(), NAME_MAX_LENGTH, false);
        ObjectNode request = requestBody(props, ctx);
        String status = ctx.resolveOptional(props, "DomainConfigurationStatus");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String region = ctx.region();

        // The configuration this resource had before, if any. It is kept when its name and the
        // createOnly properties are unchanged, and removed once its replacement exists otherwise:
        // there is no generic replacement flow, so a renamed or replaced configuration must not
        // outlive the stack that created it.
        IotDomainConfiguration prior = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId(), region) : null;
        // The store hands out its live record, and disabling the prior on the way to deleting it
        // changes that record, so the status a failed replacement has to go back to is kept here.
        String priorStatus = prior == null ? null : prior.getDomainConfigurationStatus();
        boolean sameConfiguration = prior != null && name.equals(prior.getDomainConfigurationName());
        if (sameConfiguration && sameCreateOnlyProperties(prior, request)) {
            IotDomainConfiguration updated = domainConfigurationService.updateDomainConfiguration(
                    name, updateBody(request, status, prior), region);
            reconcileTags(updated.getDomainConfigurationArn(), prior.getTags(), tags);
            record(r, name, updated);
            return;
        }
        if (sameConfiguration && (explicitName == null || explicitName.isBlank())) {
            // A createOnly property changed under a name CloudFormation generated, so the
            // replacement gets a fresh generated name, as it does on AWS. Under an explicit
            // name the create below fails with ResourceAlreadyExistsException, also as on AWS.
            name = ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
        }
        if (!tags.isEmpty()) {
            request.set("tags", tagList(tags));
        }
        IotDomainConfiguration created = domainConfigurationService.createDomainConfiguration(name, request, region);
        // Recorded at once and marked as owned by this stack: the create rollback deletes only a
        // resource that has a physical id and carries that marker, so a failure in the calls
        // below still leaves nothing behind.
        record(r, name, created);
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        try {
            if ("DISABLED".equals(status)) {
                record(r, name, domainConfigurationService.updateDomainConfiguration(name, statusBody("DISABLED"), region));
            }
            // Only a prior under another name is removed. Under the same name the create above
            // normally fails; if it went through because the prior vanished in between, the
            // configuration just created is the one to keep.
            if (prior != null && !name.equals(prior.getDomainConfigurationName())) {
                delete(r.getResourceType(), prior.getDomainConfigurationName(), region);
            }
        } catch (RuntimeException failure) {
            if (prior != null) {
                unwindReplacement(r, prior, priorStatus, name, region, failure);
            }
            throw failure;
        }
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        IotDomainConfiguration existing = findExisting(physicalId, region);
        if (existing == null) {
            return;
        }
        if ("ENABLED".equals(existing.getDomainConfigurationStatus())) {
            // Already gone is fine here too: the disable only exists to make the delete possible.
            CfnDeletes.safeDelete("domain configuration", physicalId,
                    () -> domainConfigurationService.updateDomainConfiguration(physicalId, statusBody("DISABLED"), region),
                    NOT_FOUND);
        }
        CfnDeletes.safeDelete("domain configuration", physicalId,
                () -> domainConfigurationService.deleteDomainConfiguration(physicalId, region), NOT_FOUND);
    }

    private static void record(StackResource r, String name, IotDomainConfiguration configuration) {
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", configuration.getDomainConfigurationArn());
        r.getAttributes().put("DomainType", configuration.getDomainType());
        r.getAttributes().put("ServerCertificates", serverCertificatesJson(configuration));
    }

    /**
     * A replacement failed after its new configuration existed. CloudFormationService restores the
     * previous StackResource and never learns the new name, so the configuration created here is
     * removed again, the prior one, disabled on the way to deleting it, is enabled again, and the
     * resource is pointed back at the prior configuration. The stack must report the original
     * failure, so a cleanup failure is attached to it and recorded as a rollback failure, which
     * makes the stack end in UPDATE_ROLLBACK_FAILED rather than claim the prior one is intact.
     */
    private void unwindReplacement(StackResource r, IotDomainConfiguration prior, String priorStatus, String name,
                                   String region, RuntimeException failure) {
        try {
            delete(r.getResourceType(), name, region);
            IotDomainConfiguration priorNow = findExisting(prior.getDomainConfigurationName(), region);
            if (priorNow != null && "ENABLED".equals(priorStatus)
                    && !"ENABLED".equals(priorNow.getDomainConfigurationStatus())) {
                domainConfigurationService.updateDomainConfiguration(
                        prior.getDomainConfigurationName(), statusBody("ENABLED"), region);
            }
            record(r, prior.getDomainConfigurationName(), prior);
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            String reason = "Could not roll back the replacement of domain configuration "
                    + prior.getDomainConfigurationName() + " by " + name + ": " + cleanupFailure.getMessage();
            LOG.warn(reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    private IotDomainConfiguration findExisting(String name, String region) {
        try {
            return domainConfigurationService.describeDomainConfiguration(name, region);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Domain configuration {0} is gone", name);
            return null;
        }
    }

    /** The template properties as the IoT API spells them: the same names with a lowercase first letter. */
    private static ObjectNode requestBody(JsonNode props, ProvisionContext ctx) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        JsonNode resolved = props == null ? null : ctx.engine().resolveNode(props);
        if (resolved != null && resolved.isObject()) {
            resolved.fields().forEachRemaining(field -> {
                if (!OUTSIDE_THE_REQUEST.contains(field.getKey()) && !field.getValue().isNull()) {
                    request.set(decapitalize(field.getKey()), toApiShape(field.getValue()));
                }
            });
        }
        return request;
    }

    private static JsonNode toApiShape(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> {
                if (!field.getValue().isNull()) {
                    out.set(decapitalize(field.getKey()), toApiShape(field.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(toApiShape(item)));
            return out;
        }
        return node;
    }

    private static String decapitalize(String key) {
        return key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }

    private static boolean sameCreateOnlyProperties(IotDomainConfiguration existing, JsonNode request) {
        List<String> desiredCertificates = new ArrayList<>();
        request.path("serverCertificateArns").forEach(arn -> desiredCertificates.add(arn.asText()));
        List<String> storedCertificates = existing.getServerCertificates().stream()
                .map(ServerCertificateSummary::serverCertificateArn)
                .toList();
        return Objects.equals(existing.getDomainName(), text(request, "domainName"))
                && Objects.equals(existing.getServiceType(), request.path("serviceType").asText("DATA"))
                && Objects.equals(existing.getValidationCertificateArn(), text(request, "validationCertificateArn"))
                && desiredCertificates.equals(storedCertificates);
    }

    /**
     * The in-place update: everything the API lets UpdateDomainConfiguration change. A template
     * without DomainConfigurationStatus leaves the status alone, as the AWS handler does.
     */
    private static ObjectNode updateBody(ObjectNode request, String status, IotDomainConfiguration existing) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        request.fields().forEachRemaining(field -> {
            if (!CREATE_ONLY.contains(field.getKey())) {
                body.set(field.getKey(), field.getValue());
            }
        });
        if (status != null) {
            body.put("domainConfigurationStatus", status);
        }
        if (!request.has("authorizerConfig") && existing.getAuthorizerConfig() != null) {
            body.put("removeAuthorizerConfig", true);
        }
        return body;
    }

    private static ObjectNode statusBody(String status) {
        return JsonNodeFactory.instance.objectNode().put("domainConfigurationStatus", status);
    }

    private static ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        tags.forEach((key, value) -> list.addObject().put("Key", key).put("Value", value));
        return list;
    }

    private void reconcileTags(String arn, Map<String, String> current, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            domainConfigurationService.untagResource(arn, stale);
        }
        if (!desired.isEmpty()) {
            domainConfigurationService.tagResource(arn, desired);
        }
    }

    private static String serverCertificatesJson(IotDomainConfiguration configuration) {
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        for (ServerCertificateSummary certificate : configuration.getServerCertificates()) {
            ObjectNode summary = list.addObject();
            summary.put("ServerCertificateArn", certificate.serverCertificateArn());
            summary.put("ServerCertificateStatus", certificate.serverCertificateStatus());
            if (certificate.serverCertificateStatusDetail() != null) {
                summary.put("ServerCertificateStatusDetail", certificate.serverCertificateStatusDetail());
            }
        }
        return list.toString();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
