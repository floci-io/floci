package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Emits successful secret API writes, without copying credential-bearing request bodies. */
@ApplicationScoped
public class SecretsManagerEventPublisher {
    private static final Logger LOG = Logger.getLogger(SecretsManagerEventPublisher.class);

    private final CloudTrailService cloudTrail;
    private final EventBridgeService eventBridge;
    private final AccountResolver accounts;
    private final ObjectMapper mapper;

    @Inject
    public SecretsManagerEventPublisher(CloudTrailService cloudTrail, EventBridgeService eventBridge,
                                       AccountResolver accounts, ObjectMapper mapper) {
        this.cloudTrail = cloudTrail;
        this.eventBridge = eventBridge;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    public void secretWritten(String action, JsonNode request, JsonNode response, String region, String authorization) {
        try {
            ObjectNode parameters = mapper.createObjectNode();
            copy(request, parameters, "SecretId", "secretId");
            copy(request, parameters, "ClientRequestToken", "clientRequestToken");
            copy(request, parameters, "VersionStages", "versionStages");
            copy(request, parameters, "KmsKeyId", "kmsKeyId");
            ObjectNode result = mapper.createObjectNode();
            copy(response, result, "ARN", "arn");
            copy(response, result, "Name", "name");
            copy(response, result, "VersionId", "versionId");
            cloudTrail.emitManagementEvent(new CloudTrailService.ManagementEventInput(
                    ServicePrincipals.of("secretsmanager"), action, region,
                    accounts.extractAccessKeyId(authorization), parameters, result)).ifPresent(record ->
                    eventBridge.publishServiceEvent("aws.secretsmanager", "AWS API Call via CloudTrail",
                            record.toString(), response.path("ARN").asText(), region));
        } catch (RuntimeException e) {
            // The API write is already committed. Notification failures must not turn it into a failed write.
            LOG.warnv(e, "Failed to publish Secrets Manager {0} event", action);
        }
    }

    private static void copy(JsonNode source, ObjectNode target, String from, String to) {
        JsonNode value = source.path(from);
        if (value.isTextual()) {
            target.put(to, value.asText());
        } else if ("VersionStages".equals(from) && value.isArray()) {
            value.forEach(stage -> {
                if (stage.isTextual()) {
                    target.withArray(to).add(stage.asText());
                }
            });
        }
    }
}
