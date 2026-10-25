package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class RedshiftServerlessAdminSecrets {
    private final SecretsManagerService secrets;
    private final ObjectMapper mapper;

    @Inject
    public RedshiftServerlessAdminSecrets(SecretsManagerService secrets, ObjectMapper mapper) {
        this.secrets = secrets;
        this.mapper = mapper;
    }

    void create(Namespace namespace, String kmsKeyId, String region) {
        Secret secret = secrets.createSecret("redshift-serverless/" + namespace.getNamespaceId(), value(namespace), null,
                "Managed admin credentials for Redshift Serverless namespace " + namespace.getNamespaceName(),
                kmsKeyId, List.of(), "redshift", region);
        namespace.setAdminPasswordSecretArn(secret.getArn());
        namespace.setAdminPasswordSecretKmsKeyId(kmsKeyId);
    }

    void update(Namespace namespace, String region) {
        secrets.updateSecret(namespace.getAdminPasswordSecretArn(), null,
                namespace.getAdminPasswordSecretKmsKeyId() == null ? "" : namespace.getAdminPasswordSecretKmsKeyId(), region);
        secrets.putSecretValue(namespace.getAdminPasswordSecretArn(), value(namespace), null, null, region,
                List.of("AWSCURRENT"));
    }

    void delete(Namespace namespace, String region) {
        if (namespace.getAdminPasswordSecretArn() == null) {
            return;
        }
        try {
            secrets.deleteSecret(namespace.getAdminPasswordSecretArn(), null, true, region);
        } catch (AwsException exception) {
            if (!"ResourceNotFoundException".equals(exception.getErrorCode())) {
                throw exception;
            }
            // A namespace can still be deleted when its managed secret was already removed.
        }
    }

    private String value(Namespace namespace) {
        return mapper.createObjectNode().put("engine", "redshift")
                .put("username", namespace.getAdminUsername())
                .put("password", namespace.getAdminUserPassword())
                .put("dbname", namespace.getDbName())
                .put("namespaceName", namespace.getNamespaceName()).toString();
    }
}
