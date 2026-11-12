package io.floci.aws.services.eventbridge;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.floci.aws.core.storage.AccountAwareStorageBackend.readInAccount;

/** Reads the existing rule and target records without creating a default bus or migrating legacy rows. */
final class EventBridgeTargetLookup {
    private final StorageBackend<String, EventBus> buses;
    private final StorageBackend<String, Rule> rules;
    private final StorageBackend<String, List<Target>> targets;
    private final RegionResolver resolver;

    EventBridgeTargetLookup(StorageBackend<String, EventBus> buses, StorageBackend<String, Rule> rules,
                           StorageBackend<String, List<Target>> targets, RegionResolver resolver) {
        this.buses = buses;
        this.rules = rules;
        this.targets = targets;
        this.resolver = resolver;
    }

    PaginatedResult<String> list(String targetArn, String busName, Integer limit, String nextToken, String region) {
        validateRequest(targetArn, limit, nextToken);
        String account = resolver.getAccountId();
        String partition = resolver.partitionForRegion(region);
        Scope scope = new Scope(account, partition, region, busName(busName, account, partition, region));
        requireBus(scope);
        List<String> names = matchingRuleNames(targetArn, scope);
        String namespace = cursorNamespace(scope, targetArn, busName, limit);
        return Pagination.paginate(names, Function.identity(), limit == null ? 100 : limit, nextToken, namespace,
                token -> new AwsException("InvalidToken", "Invalid NextToken.", 400));
    }

    private static void validateRequest(String targetArn, Integer limit, String nextToken) {
        if (targetArn == null || targetArn.isEmpty() || targetArn.length() > 1600) {
            throw new AwsException("ValidationException", "TargetArn must contain 1 to 1600 characters.", 400);
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            throw EventBridgeService.limitOutOfRange(limit.toString(), limit < 1);
        }
        if (nextToken != null && (nextToken.isEmpty() || nextToken.length() > 2048)) {
            throw new AwsException("ValidationException", "NextToken must contain 1 to 2048 characters.", 400);
        }
    }

    private void requireBus(Scope scope) {
        Optional<EventBus> bus = readInAccount(buses, scope.account(), "bus:" + scope.region() + ":" + scope.bus());
        if (bus.isPresent()) {
            if (!scope.bus().equals(bus.get().getName()) || !scope.arn("event-bus/" + scope.bus()).equals(bus.get().getArn())) {
                throw missingBus(scope.bus());
            }
        } else if (!"default".equals(scope.bus())) {
            throw missingBus(scope.bus());
        }
    }

    private List<String> matchingRuleNames(String targetArn, Scope scope) {
        String prefix = "rule:" + scope.region() + ":" + scope.bus() + "/";
        List<String> names = new ArrayList<>();
        for (String key : keys(scope.account(), prefix)) {
            String name = key.substring(prefix.length());
            Rule rule = readInAccount(rules, scope.account(), key).orElse(null);
            if (!ownedRule(rule, name, scope)) {
                continue;
            }
            List<Target> current = new ArrayList<>(readInAccount(targets, scope.account(), key).orElse(List.of()));
            boolean matches = current.stream().anyMatch(target -> target != null && targetArn.equals(target.getArn()));
            if (matches && readInAccount(rules, scope.account(), key).orElse(null) == rule && ownedRule(rule, name, scope)) {
                names.add(name);
            }
        }
        return names;
    }

    private static String cursorNamespace(Scope scope, String targetArn, String busName, Integer limit) {
        StringBuilder tokenScope = new StringBuilder();
        for (String value : List.of(scope.account(), scope.partition(), scope.region(), scope.bus(), targetArn,
                limit == null ? "omitted" : limit.toString(), busName == null ? "" : busName)) {
            tokenScope.append(value.length()).append(':').append(value);
        }
        return "target-rules:" + Digests.sha256Hex(tokenScope.toString());
    }

    private List<String> keys(String account, String prefix) {
        if (rules instanceof AccountAwareStorageBackend<Rule> scoped) {
            return scoped.scanAllAccountEntries(key -> key.startsWith(prefix)).stream()
                    .filter(entry -> account.equals(entry.accountId()))
                    .map(AccountAwareStorageBackend.AccountEntry::key).distinct().toList();
        }
        return rules.keys().stream().filter(key -> key.startsWith(prefix)).toList();
    }

    private static boolean ownedRule(Rule rule, String name, Scope scope) {
        if (rule == null || !name.matches("[.\\-_A-Za-z0-9]{1,64}")
                || !name.equals(rule.getName()) || !scope.bus().equals(rule.getEventBusName())
                || (rule.getAccountId() != null && !scope.account().equals(rule.getAccountId()))) {
            return false;
        }
        String resource = "default".equals(scope.bus()) ? "rule/" + name : "rule/" + scope.bus() + "/" + name;
        return scope.arn(resource).equals(rule.getArn());
    }

    private static String busName(String supplied, String account, String partition, String region) {
        if (supplied == null) {
            return "default";
        }
        if (supplied.isEmpty() || supplied.length() > 1600) {
            throw new AwsException("ValidationException", "EventBusName must contain 1 to 1600 characters.", 400);
        }
        String name = supplied;
        if (AwsArnUtils.looksLikeArn(supplied)) {
            AwsArnUtils.Arn arn;
            try {
                arn = AwsArnUtils.parse(supplied);
            } catch (IllegalArgumentException malformed) {
                throw new AwsException("ValidationException", "Invalid EventBridge event bus ARN.", 400);
            }
            if (!"events".equals(arn.service()) || !arn.resource().startsWith("event-bus/")) {
                throw new AwsException("ValidationException", "Invalid EventBridge event bus ARN.", 400);
            }
            name = arn.resource().substring("event-bus/".length());
            if (!partition.equals(arn.partition()) || !account.equals(arn.accountId()) || !region.equals(arn.region())) {
                throw missingBus(supplied);
            }
        }
        if (!name.matches("[/\\.\\-_A-Za-z0-9]+")) {
            throw new AwsException("ValidationException", "Invalid EventBridge event bus name.", 400);
        }
        return name;
    }

    private static AwsException missingBus(String name) {
        return new AwsException("ResourceNotFoundException", "Event bus not found: " + name, 400);
    }

    private record Scope(String account, String partition, String region, String bus) {
        private String arn(String resource) {
            return new AwsArnUtils.Arn(partition, "events", region, account, resource).toString();
        }
    }
}
