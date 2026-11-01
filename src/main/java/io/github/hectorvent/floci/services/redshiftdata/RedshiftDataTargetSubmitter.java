package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Submits the SQL of a Redshift Data API target of an AWS service that runs it on the customer's behalf,
 * such as an EventBridge rule or a pipe: it builds the Data API request from the target's parameters,
 * has the caller's role checked for everything the submission needs, and then calls the Data API under the
 * owning account and region. The caller decides which service principal assumes the role and how a dynamic
 * parameter path is read, so the two stay with the service whose semantics they are.
 */
@ApplicationScoped
public class RedshiftDataTargetSubmitter {

    private static final Logger LOG = Logger.getLogger(RedshiftDataTargetSubmitter.class);

    /** The Data API fields of a target, as the service stored them, before any dynamic path is resolved. */
    public record Statement(String database, String dbUser, String secretArn, String sql, List<String> sqls,
                            String statementName, Boolean withEvent) {
    }

    /** Whether the role may submit what the permissions name; a present result is why it may not. */
    @FunctionalInterface
    public interface RoleCheck {
        Optional<String> refusal(List<Permission> permissions);
    }

    private final RedshiftDataService redshiftDataService;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftDataTargetSubmitter(RedshiftDataService redshiftDataService, ObjectMapper objectMapper) {
        this.redshiftDataService = redshiftDataService;
        this.objectMapper = objectMapper;
    }

    /**
     * Submits the statement to the cluster or workgroup {@code targetArn} names. A statement with {@code Sqls}
     * is a batch, run as one transaction; the rest is a single statement.
     *
     * @param resolver turns a parameter value into the value to send, which is where a dynamic path is read
     * @param roleCheck refuses the submission when the role lacks a permission; an {@code AccessDeniedException}
     */
    public void submit(String targetArn, Statement statement, String roleArn, String account, String region,
                       UnaryOperator<String> resolver, RoleCheck roleCheck) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(targetArn);
        ObjectNode request = objectMapper.createObjectNode();
        String resource = arn.resource();
        if ("redshift".equals(arn.service()) && resource.startsWith("cluster:")) {
            request.put("ClusterIdentifier", resource.substring("cluster:".length()));
        } else if ("redshift-serverless".equals(arn.service()) && resource.startsWith("workgroup/")) {
            request.put("WorkgroupName", targetArn);
        } else {
            LOG.warnv("Redshift Data target has an unsupported resource ARN: {0}", targetArn);
            return;
        }
        putIfPresent(request, "Database", resolver.apply(statement.database()));
        putIfPresent(request, "DbUser", resolver.apply(statement.dbUser()));
        putIfPresent(request, "SecretArn", resolver.apply(statement.secretArn()));

        boolean batch = statement.sqls() != null;
        // The SQL does not change what the role needs, so the role is checked before a bad path in it is reported.
        authorize(targetArn, request, batch, account, region, roleCheck);
        putIfPresent(request, "Sql", resolver.apply(statement.sql()));
        putIfPresent(request, "StatementName", resolver.apply(statement.statementName()));
        if (batch) {
            ArrayNode sqls = request.putArray("Sqls");
            for (String sql : statement.sqls()) {
                sqls.add(resolver.apply(sql));
            }
        }
        if (statement.withEvent() != null) {
            request.put("WithEvent", statement.withEvent());
        }

        if (batch) {
            RequestScopes.callAs(account, region,
                    () -> redshiftDataService.batchExecuteStatement(request, region, roleArn));
        } else {
            RequestScopes.callAs(account, region,
                    () -> redshiftDataService.executeStatement(request, region, roleArn));
        }
        LOG.debugv("Submitted SQL to Redshift Data API: {0}", targetArn);
    }

    /**
     * The service assumes the target's role to call the Data API, so the role has to be allowed the Data
     * API action and whatever authenticates it: a secret, temporary credentials for the database user,
     * or the Serverless workgroup's credentials. A refusal is an {@code AccessDeniedException}.
     */
    private void authorize(String targetArn, ObjectNode request, boolean batch, String account, String region,
                           RoleCheck roleCheck) {
        // A cluster runs by name in the owning account, whatever account the target ARN names, so that is the
        // cluster the role has to be allowed on.
        String statementTarget = request.has("ClusterIdentifier")
                ? AwsArnUtils.Arn.of("redshift", region, account,
                        "cluster:" + request.path("ClusterIdentifier").asText()).toString()
                : targetArn;
        List<Permission> permissions = new ArrayList<>();
        permissions.add(new Permission(
                batch ? "redshift-data:BatchExecuteStatement" : "redshift-data:ExecuteStatement", statementTarget));
        // The Data API ignores a blank value, so it picks the credentials the same way.
        String secret = request.path("SecretArn").asText("");
        if (!secret.isBlank()) {
            // A secret named without its ARN has no resource to authorize against, and the Data API
            // rejects it as an invalid SecretArn before reading it, so no permission is checked for it.
            if (AwsArnUtils.isArn(secret)) {
                permissions.add(new Permission("secretsmanager:GetSecretValue", secret));
            }
        } else if (request.has("WorkgroupName")) {
            permissions.add(new Permission("redshift-serverless:GetCredentials", targetArn));
        } else if (!request.path("DbUser").asText("").isBlank()) {
            String cluster = request.path("ClusterIdentifier").asText();
            permissions.add(new Permission("redshift:GetClusterCredentials",
                    AwsArnUtils.Arn.of("redshift", region, account,
                            "dbuser:" + cluster + "/" + request.path("DbUser").asText()).toString()));
            permissions.add(new Permission("redshift:GetClusterCredentials",
                    AwsArnUtils.Arn.of("redshift", region, account,
                            "dbname:" + cluster + "/" + request.path("Database").asText()).toString()));
        }
        Optional<String> refusal = roleCheck.refusal(permissions);
        if (refusal.isPresent()) {
            throw new AwsException("AccessDeniedException", refusal.get(), 403);
        }
    }

    private static void putIfPresent(ObjectNode request, String field, String value) {
        if (value != null) {
            request.put(field, value);
        }
    }
}
