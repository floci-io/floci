package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@ApplicationScoped
public class LambdaTargetResolver {

    private final LambdaFunctionStore functionStore;
    private final LambdaAliasStore aliasStore;

    @Inject
    public LambdaTargetResolver(LambdaFunctionStore functionStore, LambdaAliasStore aliasStore) {
        this.functionStore = functionStore;
        this.aliasStore = aliasStore;
    }

    public LambdaFunction resolveInvokeTarget(String region, String name, String qualifier) {
        return resolveTarget(region, name, qualifier, this::pickAliasVersion);
    }

    /**
     * Resolves a qualifier for a <em>read</em>. Identical to the invoke path except for aliases:
     * an alias with {@code AdditionalVersionWeights} shifts traffic, so {@link #pickAliasVersion}
     * chooses randomly among the weighted versions, which is right for running the function and
     * wrong for describing it. Two reads of one alias must not disagree, so a read follows the
     * alias's primary {@code FunctionVersion}, which is what AWS reports.
     */
    public LambdaFunction resolveReadTarget(String region, String name, String qualifier) {
        return resolveTarget(region, name, qualifier, LambdaAlias::getFunctionVersion);
    }

    public LambdaFunction resolveInvokeTargetForAccount(
            String accountId, String region, String name, String qualifier) {
        return resolveTargetForAccount(accountId, region, name, qualifier, this::pickAliasVersion);
    }

    /** The read counterpart of {@link #resolveInvokeTargetForAccount}; see {@link #resolveReadTarget}. */
    public LambdaFunction resolveReadTargetForAccount(
            String accountId, String region, String name, String qualifier) {
        return resolveTargetForAccount(accountId, region, name, qualifier, LambdaAlias::getFunctionVersion);
    }

    /** The function a mapping invokes, or empty when it or its version or alias is gone. */
    public Optional<LambdaFunction> resolveMappingTarget(EventSourceMapping esm) {
        String qualifier = esm.getFunctionArn() == null
                ? null : LambdaArnUtils.resolve(esm.getFunctionArn()).qualifier();
        try {
            return Optional.of(resolveInvokeTargetForAccount(
                    esm.getAccountId(), esm.getRegion(), esm.getFunctionName(), qualifier));
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private LambdaFunction resolveTarget(String region, String name, String qualifier,
                                         Function<LambdaAlias, String> aliasVersion) {
        if (qualifier == null || qualifier.equals("$LATEST")) {
            return functionStore.get(region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Function not found: " + name, 404));
        }
        if (qualifier.chars().allMatch(Character::isDigit)) {
            return functionStore.get(region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function version not found: " + name + ":" + qualifier, 404));
        }
        LambdaAlias alias = aliasStore != null
                ? aliasStore.get(region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Alias not found: " + qualifier, 404))
                : null;
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException", "Alias not found: " + qualifier, 404);
        }
        String version = aliasVersion.apply(alias);
        if (version == null || version.equals("$LATEST")) {
            return functionStore.get(region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Function not found: " + name, 404));
        }
        return functionStore.get(region, name, version)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function version not found: " + name + ":" + version, 404));
    }

    private LambdaFunction resolveTargetForAccount(
            String accountId, String region, String name, String qualifier,
            Function<LambdaAlias, String> aliasVersion) {
        if (qualifier == null || qualifier.equals("$LATEST")) {
            return functionStore.getForAccount(accountId, region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function not found: " + name, 404));
        }
        if (qualifier.chars().allMatch(Character::isDigit)) {
            return functionStore.getForAccount(accountId, region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function version not found: " + name + ":" + qualifier, 404));
        }
        LambdaAlias alias = aliasStore != null
                ? aliasStore.getForAccount(accountId, region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Alias not found: " + qualifier, 404))
                : null;
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException", "Alias not found: " + qualifier, 404);
        }
        String version = aliasVersion.apply(alias);
        if (version == null || version.equals("$LATEST")) {
            return functionStore.getForAccount(accountId, region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function not found: " + name, 404));
        }
        return functionStore.getForAccount(accountId, region, name, version)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function version not found: " + name + ":" + version, 404));
    }

    private String pickAliasVersion(LambdaAlias alias) {
        Map<String, Double> weights = alias.getRoutingConfig();
        if (weights == null || weights.isEmpty()) {
            return alias.getFunctionVersion();
        }
        double rand = ThreadLocalRandom.current().nextDouble();
        double additionalTotal = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double primaryWeight = Math.max(0.0, 1.0 - additionalTotal);
        if (rand < primaryWeight) {
            return alias.getFunctionVersion();
        }
        double cumulative = primaryWeight;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (rand < cumulative) {
                return entry.getKey();
            }
        }
        return alias.getFunctionVersion();
    }
}
