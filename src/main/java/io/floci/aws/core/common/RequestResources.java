package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.iam.IamActionRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.Comparator;
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
 * name, or else has that id open, and so is a type whose ARN is all an id its service mints, which a request names
 * by members its ARN does not hold (a scalable target, {@code scalable-target/<id>}, by its service namespace,
 * resource id and scalable dimension), under those members' values. An ARN with a qualifier its service drops
 * to check it is checked without one (a state machine's version or alias, on the state machine). An ARN naming
 * another account or Region than the request's is checked as given and as each resource of that name a handler may
 * act on instead, in the ARN's account or the caller's and in the ARN's Region or the request's, and the request is
 * refused if any is denied, as handlers mix them (ECR's tag handlers keep the ARN's account in the request's
 * Region); one of a resource AWS owns, whose account is {@code aws}, is checked in that account alone, and so is one
 * its service says it acts on in the account the ARN names (an OAM sink a link is made to). A request leaving out a
 * member naming a resource its service then uses one of its own for is checked on the one the service finds
 * (Resource Explorer's default view).
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

    /** Whether the generated table owns this operation, including corrections to hand-built namespaces. */
    public static boolean reads(String operation) {
        return operation != null && RequestResourceMembers.table().operations().containsKey(operation);
    }

    /** Whether {@code ResourceArnBuilder} builds the resources of this namespace's requests itself. */
    public static boolean builtByHand(String namespace) {
        return namespace != null && RequestResourceMembers.table().handBuilt().contains(namespace);
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
     * mints an id after its name, or as all of its ARN, being the one {@code stored} finds for the caller under the
     * name or the members the request gives, or else its ARN with that id open, as it is with no {@code stored}; and
     * those a request names through another of its service's own, the ones {@code stored} gives, or none.
     */
    public static List<String> of(String operation, ContainerRequestContext ctx, String partition, String region,
                                  String accountId, StoredResourceArns stored) {
        return of(operation, ctx, partition, region, accountId, stored, Map.of());
    }

    /**
     * As {@link #of(String, ContainerRequestContext, String, String, String, StoredResourceArns)}, a member the table
     * locates in a REST request's path read from {@code labels}, the value the request's path gives each label of the
     * route it was routed by, and one it locates in its query from the request's query parameters.
     */
    public static List<String> of(String operation, ContainerRequestContext ctx, String partition, String region,
                                  String accountId, StoredResourceArns stored, Map<String, String> labels) {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        RequestResourceMembers.Targets targets = operation == null ? null : table.targets().get(operation);
        if (targets != null) {
            Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
            return targetArns(targets, formats(table, operation), form,
                    form.isEmpty() ? PassedRoles.jsonBody(ctx) : null, partition, region, accountId);
        }
        List<RequestResourceMembers.Resource> resources = operation == null ? null
                : served(table.operations().get(operation), ctx);
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        Map<String, String> sent = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = sent.isEmpty() ? located(table, operation, PassedRoles.jsonBody(ctx), ctx, labels) : null;
        Map<String, String> form = located(table, operation, sent, ctx, labels);
        return named(resources, formats(table, operation), form, body, partition, region, accountId, stored);
    }

    /**
     * The ARNs of the resources a JSON request body names, for a call that is not an HTTP request of its own, as a
     * state machine's task to its integrated service: as {@link #of} reads the same request, whatever endpoint it
     * would have reached.
     */
    public static List<String> ofBody(String operation, JsonNode body, String partition, String region,
                                      String accountId, StoredResourceArns stored) {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        RequestResourceMembers.Targets targets = operation == null ? null : table.targets().get(operation);
        if (targets != null) {
            return targetArns(targets, formats(table, operation), Map.of(), body, partition, region, accountId);
        }
        List<RequestResourceMembers.Resource> resources = operation == null ? null : table.operations().get(operation);
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        return named(resources, formats(table, operation), Map.of(), body, partition, region, accountId, stored);
    }

    /** Whether the table holds the operation as one with no resource of its own, which is checked on {@code *}. */
    public static boolean actsOnNoResource(String operation) {
        return reads(operation) && !hasIndividualTargets(operation)
                && RequestResourceMembers.table().operations().get(operation).isEmpty();
    }

    /** Whether the generated operation names targets in request order, one context per target. */
    public static boolean hasIndividualTargets(String operation) {
        return operation != null && RequestResourceMembers.table().targets().containsKey(operation);
    }

    private static List<String> targetArns(RequestResourceMembers.Targets targets,
                                           Map<String, RequestResourceMembers.ArnFormat> formats,
                                           Map<String, String> form, JsonNode body,
                                           String partition, String region, String accountId) {
        List<String> ids = new ArrayList<>();
        if (!form.isEmpty()) {
            for (int i = 1; form.containsKey(targets.query() + "." + i); i++) {
                ids.add(form.get(targets.query() + "." + i));
            }
        } else if (body != null && body.path(targets.member().name()).isArray()) {
            body.path(targets.member().name()).forEach(id -> ids.add(id.asText()));
        }
        List<String> arns = new ArrayList<>();
        for (String id : ids) {
            String arn = "*";
            for (Map.Entry<String, String> prefix : targets.prefixes().entrySet()) {
                if (id.startsWith(prefix.getValue())) {
                    String format = formats.get(prefix.getKey()).format();
                    Map<String, String> filling = new HashMap<>();
                    VARIABLE.matcher(format).results().map(match -> match.group(1))
                            .forEach(variable -> filling.put(variable, id));
                    arn = render(format, filling, partition, region, accountId);
                    break;
                }
            }
            arns.add(arn);
        }
        return List.copyOf(arns);
    }

    /** The ARNs of the resources the request names, as {@link #of} reads them. */
    private static List<String> named(List<RequestResourceMembers.Resource> resources,
                                      Map<String, RequestResourceMembers.ArnFormat> formats, Map<String, String> form,
                                      JsonNode body, String partition, String region, String accountId,
                                      StoredResourceArns stored) {
        Set<String> arns = new LinkedHashSet<>();
        for (RequestResourceMembers.Resource resource : resources) {
            if (!applies(resource, form, body, formats)) {
                continue;
            }
            if (resource.arn() != null) {
                for (String value : values(form, body, resource.arn())) {
                    String named = before(value, resource.before());
                    // An ARN fitting a customer's format and one AWS owns is AWS's: the account `aws` tells them apart.
                    resource.types().stream().map(formats::get).filter(format -> format.pattern().matcher(named).matches())
                            .min(Comparator.comparing(format -> !format.awsOwned()))
                            .ifPresent(format -> addNamed(arns, format.checked(named), format.awsOwned(),
                                    partition, region, accountId, stored));
                }
            } else if (resource.through() != null) {
                through(arns, resource, formats, form, body, region, stored);
            } else if (!resource.find().isEmpty()) {
                found(arns, resource, formats, form, body, partition, region, accountId, stored);
            } else if (resource.byDefault()) {
                // The one its service acts on for a request naming none, as it finds it; none when it has none.
                if (stored != null) {
                    stored.findDefault(resource.types().getFirst(), region).ifPresent(arns::add);
                }
            } else {
                fill(arns, resource, formats, form, body, partition, region, accountId, stored);
            }
        }
        return List.copyOf(arns);
    }

    /**
     * The ARNs of the resources a create makes under a parent its action acts on, from the parent's ARN in the
     * request, read as the operation's own resource reads that member, their ids open; for a create making none
     * that way, the ones its request names that the table marks {@code made}, beside others it names (a webhook,
     * not the pipeline it connects to; Cloud Map's {@code service/*}, not the namespace it is created in); empty
     * when it makes neither, so its tags go where the operation is checked. The operation is the one the request
     * is dispatched as, as for {@link #of}.
     */
    public static List<String> created(String operation, ContainerRequestContext ctx, String partition, String region,
                                       String accountId) {
        return created(operation, ctx, partition, region, accountId, Map.of());
    }

    /**
     * As {@link #created(String, ContainerRequestContext, String, String, String)}, with the path's labels as for
     * {@link #of(String, ContainerRequestContext, String, String, String, StoredResourceArns, Map)}.
     */
    public static List<String> created(String operation, ContainerRequestContext ctx, String partition, String region,
                                       String accountId, Map<String, String> labels) {
        return created(operation, null, ctx, partition, region, accountId, labels);
    }

    /**
     * As {@link #created(String, ContainerRequestContext, String, String, String, Map)}, for a request whose tags need
     * the {@code tagging} action: a create whose action acts on no resource type tags the resource it makes
     * ({@code makes}) where that action acts on the resource's type, as AWS checks a tagging action on the resource
     * being tagged, and its tags go where the operation is checked otherwise (a Cloud Map namespace, whose
     * {@code TagResource} acts on no type, on {@code *}); a null {@code tagging}, or one the table does not hold, acts
     * on it.
     */
    public static List<String> created(String operation, String tagging, ContainerRequestContext ctx, String partition,
                                       String region, String accountId, Map<String, String> labels) {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        List<RequestResourceMembers.Created> creates = operation == null ? null : table.creates().get(operation);
        List<RequestResourceMembers.Resource> made = operation == null || creates != null ? List.of()
                : served(table.operations().getOrDefault(operation, List.of()), ctx).stream()
                        .filter(RequestResourceMembers.Resource::made).toList();
        if (creates == null && made.isEmpty() && operation != null) {
            made = table.makes().getOrDefault(operation, List.of()).stream()
                    .filter(resource -> tagging == null || table.types().getOrDefault(tagging, resource.types())
                            .contains(resource.types().getFirst()))
                    .toList();
        }
        if (creates == null && made.isEmpty()) {
            return List.of();
        }
        Map<String, String> sent = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = sent.isEmpty() ? located(table, operation, PassedRoles.jsonBody(ctx), ctx, labels) : null;
        Map<String, String> form = located(table, operation, sent, ctx, labels);
        Map<String, RequestResourceMembers.ArnFormat> formats = formats(table, operation);
        if (creates == null) {
            return named(made, formats, form, body, partition, region, accountId, null);
        }
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
                        false, partition, region, accountId, null);
            }
        }
        return List.copyOf(arns);
    }

    /**
     * The request's body with each member the table locates in a REST request's path or query laid over it under the
     * member's name, so a resource reads it as it reads a member of the body: a path label's value from {@code labels},
     * a query parameter's first value; the body itself when the operation locates none. A located member is read from
     * where it is alone, as its handler reads it: one the body also gives is dropped, so a body naming a domain the
     * query does not name names nothing.
     */
    private static JsonNode located(RequestResourceMembers.Table table, String operation, JsonNode body,
                                    ContainerRequestContext ctx, Map<String, String> labels) {
        Map<String, String> located = locatedMembers(table, operation, ctx);
        if (located.isEmpty()) {
            return body;
        }
        ObjectNode overlaid = body instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        located.keySet().forEach(overlaid::remove);
        locatedValues(located, ctx, labels).forEach(overlaid::put);
        return overlaid;
    }

    /**
     * The request's form with each member the table locates laid over it, as a body has them laid over it: a REST
     * request sent as a form, to a handler that does not refuse one, is still routed by its path, so a member its path
     * or query carries is read from there alone, never from the form. The form itself when the request sends none or
     * the operation locates none.
     */
    private static Map<String, String> located(RequestResourceMembers.Table table, String operation,
                                               Map<String, String> form, ContainerRequestContext ctx,
                                               Map<String, String> labels) {
        Map<String, String> located = locatedMembers(table, operation, ctx);
        if (form.isEmpty() || located.isEmpty()) {
            return form;
        }
        Map<String, String> overlaid = new HashMap<>(form);
        overlaid.keySet().removeAll(located.keySet());
        overlaid.putAll(locatedValues(located, ctx, labels));
        return overlaid;
    }

    private static Map<String, String> locatedMembers(RequestResourceMembers.Table table, String operation,
                                                       ContainerRequestContext ctx) {
        Map<String, String> located = table.located().getOrDefault(operation, Map.of());
        Map<String, String> alternatives = IamActionRegistry.routeMemberLocations(operation, ctx);
        if (alternatives.isEmpty()) {
            return located;
        }
        Map<String, String> merged = new HashMap<>(located);
        alternatives.forEach((member, source) -> {
            if ("body".equals(source)) {
                merged.remove(member);
            } else {
                merged.put(member, source);
            }
        });
        return merged;
    }

    /**
     * The value the request gives each member the table locates, by the member's name: a path label's from
     * {@code labels}, a query parameter's first value; none for one the request does not give.
     */
    private static Map<String, String> locatedValues(Map<String, String> located, ContainerRequestContext ctx,
                                                     Map<String, String> labels) {
        MultivaluedMap<String, String> query = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters();
        Map<String, String> values = new HashMap<>();
        located.forEach((member, where) -> {
            String value = where.startsWith("{") ? labels.get(where.substring(1, where.length() - 1))
                    : query == null ? null : query.getFirst(where.substring(1));
            if (value != null) {
                values.put(member, value);
            }
        });
        return values;
    }

    /**
     * The resources that apply to a request of the service serving it: one with an {@code endpoint} applies only to
     * a request the service of that endpoint prefix serves, read from the claim of the service whose handler runs it
     * and never from the request's members, which a caller could add to a request of the other API. A request no
     * service is known to serve keeps them all, which can only check it on more.
     */
    private static List<RequestResourceMembers.Resource> served(List<RequestResourceMembers.Resource> resources,
                                                                ContainerRequestContext ctx) {
        String endpoint = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                && claim.service() != null ? claim.service().externalKey() : null;
        if (resources == null || endpoint == null) {
            return resources;
        }
        return resources.stream().filter(resource -> resource.endpoint() == null
                || resource.endpoint().equals(endpoint)).toList();
    }

    /** The value up to {@code separator}, which its handler drops the rest after; the whole value without one. */
    private static String before(String value, String separator) {
        int at = separator == null ? -1 : value.indexOf(separator);
        return at < 0 ? value : value.substring(0, at);
    }

    /**
     * Whether each member the resource is named {@code when} has one of its values and no member it is named
     * {@code unless} has one of those, an empty one for absent, or given a JSON null that the resource reads as no
     * value of its own, as a handler reading it with {@code asText(null)} does (Cloud Map's {@code NamespaceId}).
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
        RequestResourceMembers.Member read = new RequestResourceMembers.Member(member, false);
        if (!resource.absent().containsKey(member) && isNull(body, read)) {
            return "";
        }
        List<String> given = given(resource, form, body, read, formats);
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
     * none when it gives none of them. A value that is itself an ARN of the type names that resource, and the others
     * in the same list their own, as a handler taking a name or an ARN for each reads them (CodeBuild's
     * {@code BatchGetProjects}). They are in the account the resource's account member gives, which its handler
     * acts in, and none when that member holds no account id; in the request's without one, or with a JSON null for
     * it. An ARN given for a name puts the
     * resource in that ARN's account and Region, the caller's and the request's where it leaves them empty, as its
     * handler then acts on the caller's resource of that name, and it is named as an ARN the request gives is; for
     * a member naming one resource for each of a list's structures, each in the account and Region of the ARN its
     * structure gives, the request's for a name (a {@code PutEvents} entry, on the bus it names). Each value is read
     * up to the resource's {@code before} character, when it has one (a CodeBuild build id's project), and without the
     * type's own path when it begins with one, as Route 53 returns its ids ({@code /hostedzone/Z1} names hosted zone
     * {@code Z1}). A type
     * whose service mints an id after its name is the caller's resource {@code stored} finds under that name, or
     * else has that id open.
     */
    private static void fill(Set<String> arns, RequestResourceMembers.Resource resource,
                             Map<String, RequestResourceMembers.ArnFormat> formats, Map<String, String> form,
                             JsonNode body, String partition, String region, String accountId,
                             StoredResourceArns stored) {
        RequestResourceMembers.ArnFormat format = formats.get(resource.types().getFirst());
        String account = accountOf(resource, form, body, accountId);
        if (account == null) {
            return;
        }
        AwsArnUtils.Arn fromRequest = arnGiven(resource, form, body, formats);
        List<Filling> fillings = new ArrayList<>(List.of(new Filling(resource.fixed(), fromRequest)));
        boolean given = false;
        for (Map.Entry<String, RequestResourceMembers.Member> variable : resource.fill().entrySet()) {
            List<String> values = read(resource, form, body, variable.getValue()).stream()
                    .map(value -> withoutTypePath(before(value, resource.before()), resource.types().getFirst()))
                    .map(value -> asVariable(variable.getKey(), value))
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            given = true;
            RequestResourceMembers.ArnFormat named = arnOf(resource, variable.getValue().path(), formats);
            if (resource.fill().size() == 1 && fromRequest == null) {
                List<String> asArns = values.stream()
                        .filter(value -> !isArnOf(named, value)
                                && format.pattern().matcher(value).matches()).toList();
                asArns.forEach(value -> addNamed(arns, format.checked(value), format.awsOwned(), partition, region,
                        accountId, stored));
                values = values.stream().filter(value -> !asArns.contains(value)).toList();
                if (values.isEmpty()) {
                    return;
                }
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
        if (!resource.fill().isEmpty() && !given) {
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
                        resource.types().getFirst(), filling.variables(), null, region), format.awsOwned(), partition,
                        region, accountId, null);
            }
        }
    }

    /**
     * The value as the variable takes it: without the leading {@code /} a variable named for leaving it out drops,
     * as an SSM parameter's ARN names {@code /team/x} as {@code parameter/team/x}.
     */
    static String asVariable(String variable, String value) {
        return variable.endsWith("WithoutLeadingSlash") && value.startsWith("/") ? value.substring(1) : value;
    }

    /** The value without a leading {@code /<type>/}, the path a service may return an id of that type under. */
    static String withoutTypePath(String value, String type) {
        String path = "/" + type + "/";
        return value.length() > path.length() && value.startsWith(path) ? value.substring(path.length()) : value;
    }

    /**
     * The account the resource is in: the one its account member gives, the request's without one or with a JSON null
     * for it; null when that member holds no account id, which its handler refuses.
     */
    private static String accountOf(RequestResourceMembers.Resource resource, Map<String, String> form, JsonNode body,
                                    String accountId) {
        if (resource.account() == null) {
            return accountId;
        }
        List<String> given = isNull(body, resource.account()) ? List.of() : values(form, body, resource.account());
        if (given.isEmpty()) {
            return accountId;
        }
        return ACCOUNT_ID.matcher(given.getFirst()).matches() ? given.getFirst() : null;
    }

    /**
     * The resource of a type whose ARN is all an id its service mints, named by the members the table gives it
     * ({@code find}), when the request gives each one value: the caller's resource {@code stored} finds under those
     * values in the Region, or else its ARN with that id open, as for a create; in the account its account member gives
     * when it has one, which {@code stored} finds the resource of as {@code findInAccount}; none when it leaves one out
     * or gives it a JSON null, which its handler reads as none.
     */
    private static void found(Set<String> arns, RequestResourceMembers.Resource resource,
                              Map<String, RequestResourceMembers.ArnFormat> formats, Map<String, String> form,
                              JsonNode body, String partition, String region, String accountId,
                              StoredResourceArns stored) {
        List<String> names = new ArrayList<>();
        for (RequestResourceMembers.Member member : resource.find()) {
            List<String> values = values(form, body, member);
            if (isNull(body, member) || values.size() != 1) {
                if (stored != null) {
                    stored.findByMembers(resource.types().getFirst(), List.of(), region);
                }
                return;
            }
            names.add(values.getFirst());
        }
        String account = accountOf(resource, form, body, accountId);
        if (account == null) {
            return;
        }
        String type = resource.types().getFirst();
        Optional<String> found = stored == null ? Optional.empty()
                : account.equals(accountId) ? stored.findByMembers(type, names, region)
                : stored.findInAccount(type, names, account, region);
        arns.add(found.orElseGet(() -> render(formats.get(type).format(), Map.of(), partition, region, account)));
    }

    /**
     * The resources of the types a request names through a member naming another of its service's own, which runs on
     * them, is in them or reaches them ({@code through}), for each value the request gives it: the ARNs
     * {@code stored} gives for the caller's resource of that name; none for a value naming no such resource, which
     * its handler refuses.
     */
    private static void through(Set<String> arns, RequestResourceMembers.Resource resource,
                                Map<String, RequestResourceMembers.ArnFormat> formats, Map<String, String> form,
                                JsonNode body, String region, StoredResourceArns stored) {
        if (stored == null || isNull(body, resource.through())) {
            return;
        }
        for (String value : values(form, body, resource.through())) {
            stored.through(resource.through().path(), value, region).stream()
                    .filter(arn -> resource.types().stream().map(formats::get)
                            .anyMatch(format -> format.pattern().matcher(arn).matches()))
                    .forEach(arns::add);
        }
    }

    /** Whether a member that may give an ARN of a type instead of its name gives one. */
    private static boolean isArnOf(RequestResourceMembers.ArnFormat named, String value) {
        return named != null && named.pattern().matcher(value).matches();
    }

    /**
     * The ARN of a resource the namespace's service stores under the name the request gives: the one {@code stored}
     * finds the caller's resource of that name under in the Region.
     *
     * <p>The service is asked for every type of the namespace, not only for the ones whose ARN carries an id it
     * mints, so a service may answer for a type whose ARN it does not mint: a key an alias names is the key the
     * alias points at. A type whose service follows its name with an id it mints has that id open when the service
     * stores none, and any other type's is as rendered.
     */
    private static String withId(String arn, RequestResourceMembers.ArnFormat format, String type,
                                 Map<String, String> filling, StoredResourceArns stored, String region) {
        if (stored != null && filling.size() == 1) {
            Optional<String> found = stored.find(type, List.of(filling.values().iterator().next()), region);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return format.minted() ? arn + "/*" : arn;
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
     * the ARN's Region or the request's, whichever its handler acts on. An ARN with no account or Region keeps none,
     * and one of a type AWS owns ({@code awsOwned}) its account, {@code aws}, which is no caller's. One naming another
     * account whose resource {@code stored} says the service acts on there (an OAM sink a link is made to, a RAM
     * invitation sent to the caller) is that resource alone.
     */
    private static void addNamed(Set<String> arns, String arn, boolean awsOwned, String partition, String region,
                                 String accountId, StoredResourceArns stored) {
        arns.add(arn);
        if (!AwsArnUtils.isArn(arn)) {
            return;
        }
        AwsArnUtils.Arn named = AwsArnUtils.parse(arn);
        if (!awsOwned && stored != null && !named.accountId().isEmpty() && !named.accountId().equals(accountId)
                && stored.reachedInItsAccount(arn, accountId)) {
            return;
        }
        List<String> accounts = named.accountId().isEmpty() || awsOwned ? List.of(named.accountId())
                : List.of(named.accountId(), accountId);
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
        if (member.map()) {
            return;
        }
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

    /** A body's selector values, traversing structures, array elements and map values. */
    private static void addBodyValues(List<String> values, JsonNode node, RequestResourceMembers.Member member) {
        JsonNode value = node.get(member.name());
        if (value == null) {
            return;
        }
        if (member.map()) {
            if (value.isObject()) {
                for (JsonNode element : value) {
                    addElement(values, element, member.field());
                }
            }
        } else if (!member.list()) {
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
