package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
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

    /** Refuses the statement's {@code principal} when one it names does not exist. */
    void requireExisting(String region, JsonNode principal) {
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
            requireExisting(AwsRegions.partitionFor(region), value);
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
        String resource = arn.resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        if ("iam".equals(arn.service()) && "root".equals(resource)) {
            return;
        }
        if ("iam".equals(arn.service()) && resource.startsWith("role/")) {
            IamRole role = iamService.findRole(arn.accountId(), name).orElseThrow(LambdaWebPolicyPrincipals::invalid);
            if (!IamService.roleArnMatches(value, role)) {
                throw new AwsException("ValidationException", OTHER_ARN, 400);
            }
            return;
        }
        if ("iam".equals(arn.service()) && resource.startsWith("user/")) {
            IamUser user = iamService.findUser(arn.accountId(), name).orElseThrow(LambdaWebPolicyPrincipals::invalid);
            if (!resource.equals(AwsArnUtils.parse(user.getArn()).resource())) {
                throw new AwsException("ValidationException", OTHER_ARN, 400);
            }
            return;
        }
        if ("sts".equals(arn.service()) && resource.startsWith("federated-user/")) {
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
