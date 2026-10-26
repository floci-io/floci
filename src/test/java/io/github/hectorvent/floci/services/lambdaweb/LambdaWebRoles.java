package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;

/** Execution roles for tests that create Web Function revisions. */
public final class LambdaWebRoles {

    private static final String LAMBDA_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    private LambdaWebRoles() {
    }

    /** Creates the role {@code roleArn} names, trusting Lambda, unless it exists. */
    public static void trustLambda(IamService iamService, String roleArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        if (iamService.findRole(arn.accountId(), name).isEmpty()) {
            iamService.createRole(name, "/", LAMBDA_TRUST, null, 0, null);
        }
    }
}
