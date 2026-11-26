package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.iam.IamAuthorizedActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a JSON request needs from IAM, read as the enforcement filter reads the same request made directly, for a call
 * that is not an HTTP request of its own: a state machine's task to its integrated service, made as the role the task
 * runs as. That is the operation's action on each resource it names ({@link RequestResources#ofBody}), or on
 * {@code *} when it names none, as the filter falls back to, with the tags it carries where the action takes them;
 * {@code iam:PassRole} on each role it passes ({@link PassedRoles}), with {@code iam:PassedToService}; and the
 * service's tagging action on what it creates, for each set of tags it carries ({@link TagOnCreate}). A regeneration
 * of any of those tables moves the call and the direct request together.
 */
public final class RequestChecks {

    /** One action on one resource, with the condition keys the request gives it. */
    public record Check(String action, String resource, Map<String, List<String>> context) {
    }

    private RequestChecks() {
    }

    /**
     * The checks a JSON request for {@code operation}, as {@code <namespace>:<Operation>}, needs in the partition,
     * Region and account given, {@code stored} finding what it names by name ({@link StoredResourceArns}); none for an
     * operation the request-resource table does not read. A role given by name is the account's of that name. An
     * operation passing roles read by hand, from a request of its own, is not read here, and fails.
     */
    public static List<Check> of(String operation, JsonNode body, String partition, String region, String accountId,
                                 StoredResourceArns stored) {
        return of(operation, body, partition, region, accountId, stored, null);
    }

    /**
     * {@link #of(String, JsonNode, String, String, String, StoredResourceArns)}, {@code roles} finding the roles the
     * request passes as a direct request's are found; without it a role given by name is the account's of that name.
     */
    public static List<Check> of(String operation, JsonNode body, String partition, String region, String accountId,
                                 StoredResourceArns stored, PassedRoleLookups roles) {
        if (!RequestResources.reads(operation)) {
            return List.of();
        }
        int colon = operation.indexOf(':');
        String action = IamAuthorizedActions.actionFor(operation.substring(0, colon), operation.substring(colon + 1));
        List<String> named = RequestResources.actsOnNoResource(operation) ? List.of()
                : RequestResources.ofBody(operation, body, partition, region, accountId, stored);
        // A request naming none of its resources is checked on *, as the filter checks it.
        List<String> resources = named.isEmpty() ? List.of("*") : named;
        Optional<TagOnCreate.Tagging> tagging = TagOnCreate.ofBody(operation, body);
        // The operation's own action takes the tags where the table says it does, once for each reading of them.
        List<Map<String, List<String>>> contexts = tagging.filter(TagOnCreate.Tagging::requestTags)
                .map(TagOnCreate::requestTagContexts).filter(found -> !found.isEmpty()).orElse(List.of(Map.of()));
        List<Check> checks = new ArrayList<>();
        for (String resource : resources) {
            contexts.forEach(context -> checks.add(new Check(action, resource, Map.copyOf(context))));
        }
        checks.addAll(passedRoles(action, body, partition, region, accountId, roles));
        if (tagging.isPresent()) {
            // A create tags the resource it makes, not the parent it makes it in, as the filter checks it.
            List<String> created = RequestResources.created(operation, tagging.get().action(), body, partition, region,
                    accountId);
            List<String> made = created.isEmpty() ? resources : created;
            for (TagOnCreate.TagSet set : tagging.get().checked()) {
                Map<String, List<String>> context = TagOnCreate.conditionContext(tagging.get(), set, operation);
                for (String resource : TagOnCreate.resources(operation, set, partition, region, accountId, made)) {
                    checks.add(new Check(tagging.get().action(), resource, Map.copyOf(context)));
                }
            }
        }
        return List.copyOf(checks);
    }

    /**
     * {@code iam:PassRole} on each role a JSON request for {@code action}, the action a request for its operation is
     * authorized as, passes, with {@code iam:PassedToService} where AWS names the service ({@link PassedRoles}), the
     * roles found by {@code roles} in the partition, Region and account given; without it a role given by name is the
     * account's of that name and a resource holding roles holds none. An operation passing roles read by hand from a
     * request of its own, and not from a body, fails.
     */
    public static List<Check> passedRoles(String action, JsonNode body, String partition, String region,
                                          String accountId, PassedRoleLookups roles) {
        PassedRoleLookups lookups = roles != null ? roles : new PassedRoleLookups(null, List.of());
        List<Check> checks = new ArrayList<>();
        for (PassedRoles.PassedRole passed : PassedRoles.ofBody(action, body,
                lookups.in(partition, accountId, region))) {
            checks.add(new Check("iam:PassRole", passed.roleArn(), passed.passedToService() == null ? Map.of()
                    : Map.of("iam:PassedToService", List.of(passed.passedToService()))));
        }
        return checks;
    }
}
