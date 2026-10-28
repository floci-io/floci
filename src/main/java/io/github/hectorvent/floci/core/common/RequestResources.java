package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@code *} (a resource a request creates has the id its service mints). A type whose service follows the name a
 * request gives with an id it mints ({@code connection/<name>/<id>}) is the resource its service stores under that
 * name, or else has that id open. An ARN with a qualifier its service drops
 * to check it is checked without one (a state machine's version or alias, on the state machine). An ARN naming
 * another account or Region than the request's is checked as given and as each resource of that name a handler may
 * act on instead, in the ARN's account or the caller's and in the ARN's Region or the request's, and the request is
 * refused if any is denied, as handlers mix them (ECR's tag handlers keep the ARN's account in the request's
 * Region).
 */
public final class RequestResources {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(\\w+)}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");

    /** Values for a type's variables, and the ARN whose account and Region the resource they name is in, if any. */
    private record Filling(Map<String, String> variables, AwsArnUtils.Arn from) {

        Filling with(String variable, String value, AwsArnUtils.Arn arn) {
            Map<String, String> more = new HashMap<>(variables);
            more.put(variable, value);
            return new Filling(more, from == null ? arn : from);
        }
    }

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
        return of(operation, ctx, partition, region, accountId, null);
    }

    /**
     * As {@link #of(String, ContainerRequestContext, String, String, String)}, a resource of a type whose service
     * mints an id after its name being the one {@code stored} finds for the caller under the name the request gives,
     * or else its ARN with that id open, as it is with no {@code stored}.
     */
    public static List<String> of(String operation, ContainerRequestContext ctx, String partition, String region,
                                  String accountId, StoredResourceArns stored) {
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
            if (!applies(resource, form, body, formats)) {
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
                fill(arns, resource, formats, form, body, partition, region, accountId, stored);
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

    /**
     * Whether each member the resource is named {@code when} has one of its values and no member it is named
     * {@code unless} has one of those, an empty one for absent.
     */
    private static boolean applies(RequestResourceMembers.Resource resource, Map<String, String> form, JsonNode body,
                                   Map<String, RequestResourceMembers.ArnFormat> formats) {
        for (Map.Entry<String, List<String>> member : resource.when().entrySet()) {
            if (!member.getValue().contains(first(resource, form, body, member.getKey(), formats))) {
                return false;
            }
        }
        for (Map.Entry<String, List<String>> member : resource.unless().entrySet()) {
            if (member.getValue().contains(first(resource, form, body, member.getKey(), formats))) {
                return false;
            }
        }
        return true;
    }

    private static String first(RequestResourceMembers.Resource resource, Map<String, String> form, JsonNode body,
                                String member, Map<String, RequestResourceMembers.ArnFormat> formats) {
        List<String> given = given(resource, form, body, new RequestResourceMembers.Member(member, false), formats);
        return given.isEmpty() ? "" : given.getFirst();
    }

    /**
     * The values a request gives a member as the resource reads them, as {@link #read} gives them, and, for a member
     * that may give an ARN of a type instead of its name, that type's one variable of such an ARN.
     */
    private static List<String> given(RequestResourceMembers.Resource resource, Map<String, String> form,
                                      JsonNode body, RequestResourceMembers.Member member,
                                      Map<String, RequestResourceMembers.ArnFormat> formats) {
        RequestResourceMembers.ArnFormat named = arnOf(resource, member.path(), formats);
        List<String> values = read(resource, form, body, member);
        return named == null ? values : values.stream()
                .map(value -> named.pattern().matcher(value).matches() ? named.variable(value) : value).toList();
    }

    /**
     * The values a request gives a member: the one the resource reads for the member absent or given a JSON null,
     * as a handler reading it with {@code asText(null)} does (EventBridge's {@code EventBusName}), and, for a field
     * of each of a list's structures, one for each structure, that one for a structure lacking it (a
     * {@code PutEvents} entry naming no event bus is published to the default one), none for no structures.
     */
    private static List<String> read(RequestResourceMembers.Resource resource, Map<String, String> form,
                                     JsonNode body, RequestResourceMembers.Member member) {
        String absent = resource.absent().get(member.path());
        if (absent != null && member.eachElement()) {
            return eachElement(form, body, member, absent);
        }
        List<String> values = absent != null && isNull(body, member) ? List.of() : values(form, body, member);
        return values.isEmpty() && absent != null ? List.of(absent) : values;
    }

    /** The format of the type whose ARN a member of the resource may give instead of its name; null for none. */
    private static RequestResourceMembers.ArnFormat arnOf(RequestResourceMembers.Resource resource, String member,
                                                          Map<String, RequestResourceMembers.ArnFormat> formats) {
        String type = resource.arnOf().get(member);
        return type == null ? null : formats.get(type);
    }

    /**
     * The ARN a request gives for a name in a member of the resource that may give one and fills none of its
     * variables, which puts the resource in that ARN's account and Region (a rule on the default event bus of the
     * account the bus's ARN names); null for none. A member filling a variable puts each resource it names in the
     * account and Region of the ARN it gives for it, as {@link #fill} reads it.
     */
    private static AwsArnUtils.Arn arnGiven(RequestResourceMembers.Resource resource, Map<String, String> form,
                                            JsonNode body, Map<String, RequestResourceMembers.ArnFormat> formats) {
        Set<String> filled = new HashSet<>();
        resource.fill().values().forEach(member -> filled.add(member.path()));
        for (String member : resource.arnOf().keySet()) {
            if (filled.contains(member)) {
                continue;
            }
            RequestResourceMembers.ArnFormat named = arnOf(resource, member, formats);
            for (String value : values(form, body, new RequestResourceMembers.Member(member, false))) {
                if (named.pattern().matcher(value).matches()) {
                    return AwsArnUtils.parse(value);
                }
            }
        }
        return null;
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
     * no account id; in the request's without one, or with a JSON null for it. An ARN given for a name puts the
     * resource in that ARN's account and Region, the caller's and the request's where it leaves them empty, as its
     * handler then acts on the caller's resource of that name, and it is named as an ARN the request gives is; for
     * a member naming one resource for each of a list's structures, each in the account and Region of the ARN its
     * structure gives, the request's for a name (a {@code PutEvents} entry, on the bus it names). A type
     * whose service mints an id after its name is the caller's resource {@code stored} finds under that name, or
     * else has that id open.
     */
    private static void fill(Set<String> arns, RequestResourceMembers.Resource resource,
                             Map<String, RequestResourceMembers.ArnFormat> formats, Map<String, String> form,
                             JsonNode body, String partition, String region, String accountId,
                             StoredResourceArns stored) {
        RequestResourceMembers.ArnFormat format = formats.get(resource.types().getFirst());
        String account = accountId;
        if (resource.account() != null) {
            List<String> given = isNull(body, resource.account()) ? List.of() : values(form, body, resource.account());
            if (!given.isEmpty() && !ACCOUNT_ID.matcher(given.getFirst()).matches()) {
                return;
            }
            account = given.isEmpty() ? accountId : given.getFirst();
        }
        AwsArnUtils.Arn fromRequest = arnGiven(resource, form, body, formats);
        List<Filling> fillings = new ArrayList<>(List.of(new Filling(Map.of(), fromRequest)));
        for (Map.Entry<String, RequestResourceMembers.Member> variable : resource.fill().entrySet()) {
            List<String> values = read(resource, form, body, variable.getValue());
            if (values.isEmpty()) {
                continue;
            }
            RequestResourceMembers.ArnFormat named = arnOf(resource, variable.getValue().path(), formats);
            if (resource.fill().size() == 1 && fromRequest == null && values.stream().allMatch(value ->
                    !isArnOf(named, value) && format.pattern().matcher(value).matches())) {
                values.forEach(value -> addNamed(arns, format.checked(value), partition, region, accountId));
                return;
            }
            List<Filling> next = new ArrayList<>();
            for (Filling filling : fillings) {
                for (String value : values) {
                    next.add(isArnOf(named, value)
                            ? filling.with(variable.getKey(), named.variable(value), AwsArnUtils.parse(value))
                            : filling.with(variable.getKey(), value, null));
                }
            }
            fillings = next;
        }
        if (!resource.fill().isEmpty() && fillings.getFirst().variables().isEmpty()) {
            return;
        }
        for (Filling filling : fillings) {
            AwsArnUtils.Arn from = filling.from();
            if (from == null) {
                arns.add(withId(render(format.format(), filling.variables(), partition, region, account), format,
                        resource.types().getFirst(), filling.variables(), account.equals(accountId) ? stored : null,
                        region));
            } else {
                addNamed(arns, withId(render(format.format(), filling.variables(), from.partition(),
                        from.region().isEmpty() ? region : from.region(),
                        from.accountId().isEmpty() ? accountId : from.accountId()), format,
                        resource.types().getFirst(), filling.variables(), null, region), partition, region, accountId);
            }
        }
    }

    /** Whether a member that may give an ARN of a type instead of its name gives one. */
    private static boolean isArnOf(RequestResourceMembers.ArnFormat named, String value) {
        return named != null && named.pattern().matcher(value).matches();
    }

    /**
     * The ARN of a resource of a type whose service follows its name with an id it mints: the one {@code stored}
     * finds the caller's resource of that name under in the Region, or else the ARN with that id open; any other
     * type's as rendered.
     */
    private static String withId(String arn, RequestResourceMembers.ArnFormat format, String type,
                                 Map<String, String> filling, StoredResourceArns stored, String region) {
        if (!format.minted()) {
            return arn;
        }
        if (stored != null && filling.size() == 1) {
            Optional<String> found = stored.find(type, filling.values().iterator().next(), region);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return arn + "/*";
    }

    /**
     * Whether a body gives the member a JSON null, which a handler reading it with {@code asText(null)} reads as
     * none (ECR's {@code registryId}, its caller's registry then; EventBridge's {@code EventBusName}, the default bus).
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
     * a number, a boolean or a null, and a list member's only from a list, a single value giving none. A member
     * with a field gives that field of its structure, or of each of its list's structures.
     */
    private static List<String> values(Map<String, String> form, JsonNode body, RequestResourceMembers.Member member) {
        List<String> values = new ArrayList<>();
        if (!form.isEmpty()) {
            addFormValues(values, form, "", member);
        } else if (body != null && body.isObject()) {
            addBodyValues(values, body, member);
        }
        return values;
    }

    /**
     * The value of a field of each of a list's structures, {@code absent} for one that gives none or a JSON null, as
     * a handler reading it with {@code asText(null)} does: a body's list, or a form's list numbered from 1 to the
     * first structure it gives nothing of; none for no list.
     */
    private static List<String> eachElement(Map<String, String> form, JsonNode body,
                                            RequestResourceMembers.Member member, String absent) {
        List<String> values = new ArrayList<>();
        if (!form.isEmpty()) {
            String key = member.name() + ".member.";
            for (int i = 1; hasElement(form, key + i + "."); i++) {
                List<String> found = new ArrayList<>();
                add(found, form.get(key + i + "." + member.field().name()));
                values.add(found.isEmpty() ? absent : found.getFirst());
            }
        } else if (body != null && body.isObject() && body.path(member.name()).isArray()) {
            for (JsonNode element : body.path(member.name())) {
                List<String> found = new ArrayList<>();
                JsonNode value = element.path(member.field().name());
                if (!value.isNull()) {
                    addValue(found, value);
                }
                values.add(found.isEmpty() ? absent : found.getFirst());
            }
        }
        return values;
    }

    /** Whether a form gives anything of a list's structure under {@code prefix}. */
    private static boolean hasElement(Map<String, String> form, String prefix) {
        return form.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    /**
     * A form's values of a member under {@code prefix}: each element of a list from 1 to the first that does not
     * give the member, or its field, as a handler loops over them.
     */
    private static void addFormValues(List<String> values, Map<String, String> form, String prefix,
                                      RequestResourceMembers.Member member) {
        String key = prefix + member.name();
        if (!member.list()) {
            if (member.field() == null) {
                add(values, form.get(key));
            } else {
                addFormValues(values, form, key + ".", member.field());
            }
            return;
        }
        for (int i = 1; given(form, key + ".member." + i, member.field()); i++) {
            if (member.field() == null) {
                add(values, form.get(key + ".member." + i));
            } else {
                addFormValues(values, form, key + ".member." + i + ".", member.field());
            }
        }
    }

    /** Whether a form gives a list's element, or the field of it that is read. */
    private static boolean given(Map<String, String> form, String element, RequestResourceMembers.Member field) {
        if (field == null) {
            return form.containsKey(element);
        }
        return form.containsKey(element + "." + field.name() + (field.list() ? ".member.1" : ""));
    }

    /** A body's values of a member, and of its field in its structure or in each of its list's structures. */
    private static void addBodyValues(List<String> values, JsonNode node, RequestResourceMembers.Member member) {
        JsonNode value = node.get(member.name());
        if (value == null) {
            return;
        }
        if (!member.list()) {
            addElement(values, value, member.field());
        } else if (value.isArray()) {
            for (JsonNode element : value) {
                addElement(values, element, member.field());
            }
        }
    }

    private static void addElement(List<String> values, JsonNode element, RequestResourceMembers.Member field) {
        if (field == null) {
            addValue(values, element);
        } else if (element.isObject()) {
            addBodyValues(values, element, field);
        }
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
