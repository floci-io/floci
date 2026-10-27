package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether the principals of a resource policy exist, as Lambda checks them before it stores the policy. A role
 * or a user is looked up in the account its ARN names, or in the function's account by its unique id, and an ARN
 * that names one under another path is refused on its own. Floci holds every account, so an account id or its
 * root always exists, and it keeps no list of service principals, so those are not checked.
 */
@ApplicationScoped
public class LambdaWebPolicyPrincipals {

    private static final String OTHER_ARN = "The principal arn provided does not match the actual principal arn."
            + " Please check your input and try again.";
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");

    private final IamService iamService;

    @Inject
    public LambdaWebPolicyPrincipals(IamService iamService) {
        this.iamService = iamService;
    }

    /** Refuses the statement's {@code principal} when one it names does not exist in {@code partition}. */
    void requireExisting(String partition, JsonNode principal) {
        if (!principal.isObject()) {
            return;
        }
        if (principal.has("CanonicalUser")) {
            throw invalid();
        }
        JsonNode aws = principal.get("AWS");
        if (aws == null) {
            return;
        }
        List<String> values = new ArrayList<>();
        if (aws.isTextual()) {
            values.add(aws.textValue());
        } else {
            aws.forEach(value -> values.add(value.textValue()));
        }
        for (String value : values) {
            requireExisting(partition, value);
        }
    }

    private void requireExisting(String partition, String value) {
        if ("*".equals(value) || ACCOUNT_ID.matcher(value).matches()) {
            return;
        }
        if (!AwsArnUtils.isArn(value)) {
            if (!heldUniqueId(value)) {
                throw invalid();
            }
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        if (!arn.partition().equals(partition) || !arn.region().isEmpty()
                || !ACCOUNT_ID.matcher(arn.accountId()).matches()) {
            throw invalid();
        }
        if ("iam".equals(arn.service())) {
            String held = iamService.heldPrincipalArn(value).orElseThrow(LambdaWebPolicyPrincipals::invalid);
            if (!AwsArnUtils.parse(held).resource().equals(arn.resource())) {
                throw new AwsException("ValidationException", OTHER_ARN, 400);
            }
            return;
        }
        if ("sts".equals(arn.service()) && arn.resource().startsWith("federated-user/")) {
            return;
        }
        throw invalid();
    }

    /** A role's or a user's unique id, as IAM gives it to one in the function's account. */
    private boolean heldUniqueId(String id) {
        return iamService.listRoles("/").stream().anyMatch(role -> id.equals(role.getRoleId()))
                || iamService.listUsers("/").stream().anyMatch(user -> id.equals(user.getUserId()));
    }

    private static AwsException invalid() {
        return new AwsException("ValidationException", LambdaWebService.INVALID_PRINCIPAL, 400);
    }
}
