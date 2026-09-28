package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.DenyField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedDirective;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precomputes which {@code (typeName, fieldName)} coordinates a query must have redacted
 * before it reaches the (AWS-agnostic) GraphQL sidecar's {@code /v1/execute}, what used to be
 * live per-field checks made during in-process execution, back when execution happened in
 * Floci's own process (issue #2917). Takes the {@link PlanResult} from {@code
 * GraphqlSidecarClient#plan} (the caller fetches it once and reuses it for the subscription
 * check too) and hands its fields to {@link AppSyncAuthRequirements} for the actual "what does
 * {@code @aws_auth} mean" interpretation.
 */
@ApplicationScoped
public class SidecarFieldAuthorizationPlanner {

    private final IamAuthValidator iamAuthValidator;

    @Inject
    public SidecarFieldAuthorizationPlanner(IamAuthValidator iamAuthValidator) {
        this.iamAuthValidator = iamAuthValidator;
    }

    /** Empty when nothing needs redacting (including when {@code auth} carries no API context, same as the live path). */
    public List<DenyField> planDenyFields(PlanResult plan, AppSyncAuthContext auth) {
        if (auth == null || auth.graphqlApi() == null) {
            return List.of();
        }
        List<DenyField> denied = new ArrayList<>();
        for (PlannedField field : plan.fields()) {
            if (isDenied(field, auth)) {
                denied.add(new DenyField(field.typeName(), field.fieldName(),
                        AppSyncAuth.FIELD_UNAUTHORIZED_TYPE,
                        AppSyncAuth.fieldUnauthorizedMessage(field.fieldName(), field.typeName())));
            }
        }
        return denied;
    }

    private boolean isDenied(PlannedField field, AppSyncAuthContext auth) {
        if (isDeniedField(field, auth)) {
            return true;
        }
        if (!modeAllowed(field, auth)) {
            return true;
        }
        if (auth.authenticationType() == AuthenticationType.AWS_IAM) {
            String fieldArn = IamAuthValidator.fieldArn(
                    auth.region(), auth.accountId(), auth.graphqlApi().getApiId(), field.typeName(), field.fieldName());
            if (iamAuthValidator.isFieldDenied(auth.accessKeyId(), fieldArn)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeniedField(PlannedField field, AppSyncAuthContext auth) {
        Set<String> denied = auth.deniedFields();
        if (denied.isEmpty()) {
            return false;
        }
        if (denied.contains(field.typeName() + "." + field.fieldName())) {
            return true;
        }
        String arn = IamAuthValidator.fieldArn(
                auth.region(), auth.accountId(), auth.graphqlApi().getApiId(), field.typeName(), field.fieldName());
        return denied.contains(arn);
    }

    private boolean modeAllowed(PlannedField field, AppSyncAuthContext auth) {
        List<AppSyncAuthRequirements.AuthRequirement> requirements =
                AppSyncAuthRequirements.requirementsFrom(toDirectiveUse(field.directives()), auth.graphqlApi());
        if (requirements.isEmpty()) {
            requirements =
                    AppSyncAuthRequirements.requirementsFrom(toDirectiveUse(field.typeDirectives()), auth.graphqlApi());
        }
        if (requirements.isEmpty()) {
            return auth.authenticationType() == auth.graphqlApi().getAuthenticationType();
        }
        for (AppSyncAuthRequirements.AuthRequirement requirement : requirements) {
            if (requirement.mode() == auth.authenticationType()
                    && AppSyncAuthRequirements.groupsAllowed(requirement, auth)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<AppSyncAuthRequirements.DirectiveUse> toDirectiveUse(List<PlannedDirective> directives) {
        List<AppSyncAuthRequirements.DirectiveUse> uses = new ArrayList<>();
        for (PlannedDirective directive : directives) {
            List<String> groups = List.of();
            if (directive.args() instanceof Map<?, ?> args) {
                Object raw = args.get("cognito_groups");
                if (raw instanceof List<?> list) {
                    groups = list.stream().map(String::valueOf).toList();
                }
            }
            uses.add(new AppSyncAuthRequirements.DirectiveUse(directive.name(), groups));
        }
        return uses;
    }
}
