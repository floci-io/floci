package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;

import java.util.Optional;

final class EsmFunctionResolver {

    private EsmFunctionResolver() {
    }

    static Optional<LambdaFunction> resolve(LambdaFunctionStore functionStore, LambdaAliasStore aliasStore,
                                            EventSourceMapping esm) {
        String qualifier = esm.getFunctionArn() == null
                ? null : LambdaArnUtils.resolve(esm.getFunctionArn()).qualifier();
        if (qualifier == null || qualifier.equals("$LATEST")) {
            return functionStore.getForAccount(esm.getAccountId(), esm.getRegion(), esm.getFunctionName());
        }
        String version = qualifier;
        if (!qualifier.chars().allMatch(Character::isDigit)) {
            Optional<LambdaAlias> alias = aliasStore.getForAccount(
                    esm.getAccountId(), esm.getRegion(), esm.getFunctionName(), qualifier);
            if (alias.isEmpty()) {
                return Optional.empty();
            }
            version = alias.get().getFunctionVersion();
            if (version == null || version.equals("$LATEST")) {
                return functionStore.getForAccount(esm.getAccountId(), esm.getRegion(), esm.getFunctionName());
            }
        }
        return functionStore.getForAccount(esm.getAccountId(), esm.getRegion(), esm.getFunctionName(), version);
    }
}
