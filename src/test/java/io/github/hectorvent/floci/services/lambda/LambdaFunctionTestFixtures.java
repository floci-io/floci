package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;

final class LambdaFunctionTestFixtures {

    private LambdaFunctionTestFixtures() {
    }

    static LambdaFunction function(String account, String region, String name, String version) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setAccountId(account);
        String arn = AwsArnUtils.Arn.of("lambda", region, account, "function:" + name).toString();
        function.setFunctionArn(arn + ("$LATEST".equals(version) ? "" : ":" + version));
        function.setVersion(version);
        function.setRevisionId(account + region + version);
        return function;
    }

    static LambdaAlias alias(String account, String region, String function, String name, String version) {
        LambdaAlias alias = new LambdaAlias();
        alias.setName(name);
        alias.setFunctionName(function);
        alias.setFunctionVersion(version);
        alias.setAliasArn(AwsArnUtils.Arn.of("lambda", region, account, "function:" + function + ":" + name).toString());
        return alias;
    }
}
