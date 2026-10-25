package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resources a request names, as ARNs, for the namespaces {@link RequestResourceMembers} reads: those whose
 * ARNs {@code ResourceArnBuilder} does not build itself, which are otherwise checked on {@code *}, so a grant
 * scoped to a resource is refused and a deny scoped to one never applies.
 *
 * <p>A request names a resource by a member holding its ARN, which counts when it matches the ARN format of a
 * resource type the action acts on, or by members filling the variables of a type's format, the others open as
 * {@code *} (a resource a request creates has the id its service mints). An ARN with a qualifier its service drops
 * to check it is checked without one (a state machine's version or alias, on the state machine). An ARN naming
 * another account or Region than the request's is checked as given and as each resource of that name a handler may
 * act on instead, in the ARN's account or the caller's and in the ARN's Region or the request's, and the request is
 * refused if any is denied, as handlers mix them (ECR's tag handlers keep the ARN's account in the request's
 * Region).
 */
public final class RequestResources {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(\\w+)}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");

    private RequestResources() {
    }

    /**
     * The ARNs of the resources a request names, in the request's partition, Region and account: empty when the
     * table does not read the operation or its request names none, so it is checked on {@code *}. The operation,
     * as {@code <namespace>:<Operation>}, is the one the request is dispatched as, never read from the request
     * again, whose {@code X-Amz-Target} or {@code Action} may name another.
     */
    public static List<String> of(String operation, ContainerRequestContext ctx, String partition, String region,
                                  String accountId) {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        List<RequestResourceMembers.Resource> resources = operation == null ? null : table.operations().get(operation);
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = form.isEmpty() ? PassedRoles.jsonBody(ctx) : null;
        Map<String, RequestResourceMembers.ArnFormat> formats = formats(table, operation);
        Set<String> arns = new LinkedHashSet<>();
        for (RequestResourceMembers.Resource resource : resources) {
            if (!applies(resource, form, body)) {
                continue;
            }
            if (resource.arn() != null) {
                for (String value : values(form, body, resource.arn())) {
                    String named = before(value, resource.before());
                    resource.types().stream().map(formats::get).filter(format -> format.pattern().matcher(named).matches())
                            .findFirst().ifPresent(format -> addNamed(arns, format.checked(named), partition, region,
                                    accountId));
                }
            } else {
                fill(arns, resource, formats.get(resource.types().getFirst()), form, body, partition, region, accountId);
            }
        }
        return List.copyOf(arns);
    }

    /**
     * The ARNs of the resources a create makes under a parent its action acts on, from the parent's ARN in the
     * request, read as the operation's own resource reads that member, their ids open; empty when it makes none,
     * so its tags go where the operation is checked. The operation is the one the request is dispatched as, as for
     * {@link #of}.
     */
    public static List<String> created(String operation, ContainerRequestContext ctx, String partition, String region,
                                       String accountId) {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        List<RequestResourceMembers.Created> creates = operation == null ? null : table.creates().get(operation);
        if (creates == null) {
            return List.of();
        }
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = form.isEmpty() ? PassedRoles.jsonBody(ctx) : null;
        Map<String, RequestResourceMembers.ArnFormat> formats = formats(table, operation);
        Set<String> arns = new LinkedHashSet<>();
        for (RequestResourceMembers.Created created : creates) {
            String separator = table.operations().getOrDefault(operation, List.of()).stream()
                    .filter(resource -> created.arn().equals(resource.arn()) && resource.before() != null)
                    .map(RequestResourceMembers.Resource::before).findFirst().orElse(null);
            RequestResourceMembers.ArnFormat parentFormat = formats.get(created.parent());
            for (String value : values(form, body, created.arn())) {
                String named = before(value, separator);
                if (!parentFormat.pattern().matcher(named).matches()) {
                    continue;
                }
                AwsArnUtils.Arn parent = AwsArnUtils.parse(parentFormat.checked(named));
                String under = parent.resource().substring(created.parentWord().length());
                addNamed(arns, new AwsArnUtils.Arn(parent.partition(), parent.service(), parent.region(),
                        parent.accountId(), created.childWord() + under + created.separator() + "*").toString(),
                        partition, region, accountId);
            }
        }
        return List.copyOf(arns);
    }

    /** The value up to {@code separator}, which its handler drops the rest after; the whole value without one. */
    private static String before(String value, String separator) {
        int at = separator == null ? -1 : value.indexOf(separator);
        return at < 0 ? value : value.substring(0, at);
    }

    /** Whether each member the resource is named {@code when} has one of its values, an empty one for absent. */
    private static boolean applies(RequestResourceMembers.Resource resource, Map<String, String> form, JsonNode body) {
        for (Map.Entry<String, List<String>> member : resource.when().entrySet()) {
            List<String> values = values(form, body, new RequestResourceMembers.Member(member.getKey(), false));
            if (!member.getValue().contains(values.isEmpty() ? "" : values.getFirst())) {
                return false;
            }
        }
        return true;
    }

    /** The ARN formats of the operation's namespace, the part of {@code <namespace>:<Operation>} before its colon. */
    private static Map<String, RequestResourceMembers.ArnFormat> formats(RequestResourceMembers.Table table,
                                                                       String operation) {
        return table.formats().getOrDefault(operation.substring(0, operation.indexOf(':')), Map.of());
    }

    /**
     * The ARNs of a type the request names through the variables it fills: one for each value of a list member,
     * none when it gives none of them. A value that is itself an ARN of the type names that resource. They are in
     * the account the resource's account member gives, which its handler acts in, and none when that member holds
     * no account id; in the request's without one, or with a JSON null for it.
     */
    private static void fill(Set<String> arns, RequestResourceMembers.Resource resource,
                             RequestResourceMembers.ArnFormat format, Map<String, String> form, JsonNode body,
                             String partition, String region, String accountId) {
        String account = accountId;
        if (resource.account() != null) {
            List<String> given = isNull(body, resource.account()) ? List.of() : values(form, body, resource.account());
            if (!given.isEmpty() && !ACCOUNT_ID.matcher(given.getFirst()).matches()) {
                return;
            }
            account = given.isEmpty() ? accountId : given.getFirst();
        }
        List<Map<String, String>> fillings = new ArrayList<>(List.of(Map.of()));
        for (Map.Entry<String, RequestResourceMembers.Member> variable : resource.fill().entrySet()) {
            List<String> values = values(form, body, variable.getValue());
            if (values.isEmpty()) {
                continue;
            }
            if (resource.fill().size() == 1 && values.stream().allMatch(value -> format.pattern().matcher(value).matches())) {
                values.forEach(value -> addNamed(arns, format.checked(value), partition, region, accountId));
                return;
            }
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> filling : fillings) {
                for (String value : values) {
                    Map<String, String> more = new HashMap<>(filling);
                    more.put(variable.getKey(), value);
                    next.add(more);
                }
            }
            fillings = next;
        }
        if (!resource.fill().isEmpty() && fillings.getFirst().isEmpty()) {
            return;
        }
        for (Map<String, String> filling : fillings) {
            arns.add(render(format.format(), filling, partition, region, account));
        }
    }

    /**
     * Whether a body gives the member a JSON null, which a handler reading an account with {@code asText(null)} reads
     * as none (ECR's {@code registryId}, its caller's registry then).
     */
    private static boolean isNull(JsonNode body, RequestResourceMembers.Member member) {
        return body != null && body.isObject() && body.path(member.name()).isNull();
    }

    /** The format with each variable replaced once: the partition, Region and account, a value, or else {@code *}. */
    private static String render(String format, Map<String, String> filling, String partition, String region,
                                 String accountId) {
        Matcher variable = VARIABLE.matcher(format);
        StringBuilder arn = new StringBuilder();
        while (variable.find()) {
            String value = switch (variable.group(1)) {
                case "Partition" -> partition;
                case "Region" -> region;
                case "Account" -> accountId;
                default -> filling.getOrDefault(variable.group(1), "*");
            };
            variable.appendReplacement(arn, Matcher.quoteReplacement(value));
        }
        return variable.appendTail(arn).toString();
    }

    /**
     * An ARN the request names: as given, and, when it names another partition, account or Region than the
     * request's, as each resource of that name in the request's partition, in the ARN's account or the caller's and
     * the ARN's Region or the request's, whichever its handler acts on. An ARN with no account or Region keeps none.
     */
    private static void addNamed(Set<String> arns, String arn, String partition, String region, String accountId) {
        arns.add(arn);
        if (!AwsArnUtils.isArn(arn)) {
            return;
        }
        AwsArnUtils.Arn named = AwsArnUtils.parse(arn);
        List<String> accounts = named.accountId().isEmpty() ? List.of("") : List.of(named.accountId(), accountId);
        List<String> regions = named.region().isEmpty() ? List.of("") : List.of(named.region(), region);
        for (String account : accounts) {
            for (String inRegion : regions) {
                arns.add(new AwsArnUtils.Arn(partition, named.service(), inRegion, account, named.resource()).toString());
            }
        }
    }

    /**
     * The non-empty strings a request gives a member, read as its handler reads them: a form's parameter, or its
     * list numbered under {@code member} from 1 to the first missing, or a body's value, as text whether a string,
     * a number, a boolean or a null, and a list member's only from a list, a single value giving none.
     */
    private static List<String> values(Map<String, String> form, JsonNode body, RequestResourceMembers.Member member) {
        List<String> values = new ArrayList<>();
        if (!form.isEmpty()) {
            if (!member.list()) {
                add(values, form.get(member.name()));
                return values;
            }
            for (int i = 1; form.containsKey(member.name() + ".member." + i); i++) {
                add(values, form.get(member.name() + ".member." + i));
            }
            return values;
        }
        JsonNode value = body == null || !body.isObject() ? null : body.get(member.name());
        if (value == null) {
            return values;
        }
        if (!member.list()) {
            addValue(values, value);
        } else if (value.isArray()) {
            for (JsonNode element : value) {
                addValue(values, element);
            }
        }
        return values;
    }

    /**
     * A body's value as text, as a handler's {@code asText()} reads it, a null as {@code null}, and nothing for a
     * structure, which a handler reads as blank.
     */
    private static void addValue(List<String> values, JsonNode value) {
        if (value.isValueNode()) {
            add(values, value.asText());
        }
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }
}
