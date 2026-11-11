package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestResourceMembers.ArnFormat;
import io.floci.aws.core.common.RequestResourceMembers.Member;
import io.floci.aws.core.common.RequestResourceMembers.Resource;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The vendored table of the resources each operation's request names, and that {@link RequestResources} reads every
 * resource it gives, from a JSON body and from Query form parameters.
 */
class RequestResourceMembersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatedIdTargetsValidateTheirMemberWireNameAndDiscriminators() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = (ObjectNode) mapper.readTree("""
                {"namespaces":{"ec2":["ec2"]},"arnFormats":{"ec2":{
                "instance":"arn:${Partition}:ec2:${Region}:${Account}:instance/${InstanceId}",
                "volume":"arn:${Partition}:ec2:${Region}:${Account}:volume/${VolumeId}"}},
                "operations":{"ec2:Tag":{"types":["instance","volume"],"resources":[],
                "targets":{"member":"Resources[]","query":"ResourceId","prefixes":{"instance":"i-"}}}}}
                """);
        assertEquals("ResourceId", parse(document.toString()).targets().get("ec2:Tag").query());
        for (String invalid : List.of(
                "{\"member\":\"Resources\",\"query\":\"ResourceId\",\"prefixes\":{\"instance\":\"i-\"}}",
                "{\"member\":\"Resources[]\",\"query\":\"ResourceId.member\",\"prefixes\":{\"instance\":\"i-\"}}",
                "{\"member\":\"Resources[]\",\"query\":\"ResourceId\",\"prefixes\":{\"unknown\":\"i-\"}}",
                "{\"member\":\"Resources[]\",\"query\":\"ResourceId\",\"prefixes\":{\"instance\":\"\"}}",
                "{\"member\":\"Resources[]\",\"query\":\"ResourceId\",\"prefixes\":{\"instance\":\"i-\",\"volume\":\"i-\"}}")) {
            ObjectNode changed = document.deepCopy();
            ((ObjectNode) changed.path("operations").path("ec2:Tag")).set("targets", mapper.readTree(invalid));
            assertThrows(IllegalStateException.class, () -> parse(changed.toString()), invalid);
        }
    }

    @Test
    void theTableReadsItsNamespacesAndGivesEachTypeItNamesAnArnFormat() {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        assertTrue(table.namespaces().containsAll(List.of("cognito-idp", "ecr", "elasticloadbalancing", "states")));
        assertTrue(table.handBuilt().contains("lambda"));
        assertEquals(Set.of("dynamodb", "logs"), table.namespaces().stream().filter(table.handBuilt()::contains)
                .collect(Collectors.toSet()));
        Set<String> logsDelivery = Stream.of("PutDeliverySource", "GetDeliverySource", "DeleteDeliverySource",
                "DescribeDeliverySources", "PutDeliveryDestination", "GetDeliveryDestination",
                "DeleteDeliveryDestination", "DescribeDeliveryDestinations", "CreateDelivery", "GetDelivery",
                "UpdateDeliveryConfiguration", "DeleteDelivery", "DescribeDeliveries")
                .map(operation -> "logs:" + operation).collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(logsDelivery);
        expected.add("dynamodb:ListTables");
        assertEquals(expected, table.operations().keySet().stream()
                .filter(operation -> table.handBuilt().contains(operation.substring(0, operation.indexOf(':'))))
                .collect(Collectors.toSet()),
                "only explicit corrections and the operations listed to read override hand-built operations");
        assertTrue(table.namespaces().stream().noneMatch(table.notCovered()::contains));
        assertTrue(table.otherNamespaces().stream().noneMatch(table.notCovered()::contains));
        table.operations().forEach((operation, resources) -> {
            Map<String, ArnFormat> formats = table.formats().get(operation.substring(0, operation.indexOf(':')));
            resources.forEach(resource -> assertTrue(formats.keySet().containsAll(resource.types()), operation));
        });
    }

    @Test
    void anArnFormatMatchesAnArnOfItsTypeAndNoOther() {
        ArnFormat group = format("arn:${Partition}:elasticloadbalancing:${Region}:${Account}:targetgroup/${TargetGroupName}/${TargetGroupId}");
        assertTrue(group.pattern().matcher("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/abc").matches());
        assertTrue(group.pattern().matcher("arn:aws-cn:elasticloadbalancing:cn-north-1:000000000000:targetgroup/a/b").matches());
        assertFalse(group.pattern().matcher("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/a/b").matches());
        assertFalse(group.pattern().matcher("arn:aws:iam::000000000000:role/targetgroup/a/b").matches());
        assertFalse(group.pattern().matcher("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup//b").matches());
    }

    @Test
    void anUnqualifiedTypesArnIsCheckedWithoutItsQualifier() {
        String machine = "arn:aws:states:us-east-1:000000000000:stateMachine:orders";
        String format = "arn:${Partition}:states:${Region}:${Account}:stateMachine:${StateMachineName}";
        ArnFormat unqualified = new ArnFormat(format, RequestResourceMembers.pattern(format), true);
        assertEquals(machine, unqualified.checked(machine + ":PROD"));
        assertEquals(machine, unqualified.checked(machine));
        assertEquals(machine + ":PROD", format(unqualified.format()).checked(machine + ":PROD"));
        assertTrue(RequestResourceMembers.table().formats().get("states").get("statemachine").unqualified());
        assertFalse(RequestResourceMembers.table().formats().get("states").get("execution").unqualified());
    }

    @Test
    void connectionsAndApiDestinationsAreOfTypesWhoseServiceMintsAnIdAfterTheirName() {
        Map<String, ArnFormat> events = RequestResourceMembers.table().formats().get("events");
        assertTrue(events.get("connection").minted());
        assertTrue(events.get("api-destination").minted());
        assertFalse(events.get("event-bus").minted());
    }

    @Test
    void aScalableTargetIsFoundByTheMembersItsServiceReadsNotFilledByItsResourceId() {
        List<Member> triple = List.of(new Member("ServiceNamespace", false), new Member("ResourceId", false),
                new Member("ScalableDimension", false));
        for (String operation : List.of("RegisterScalableTarget", "DeregisterScalableTarget", "PutScalingPolicy",
                "DeleteScalingPolicy", "PutScheduledAction", "DeleteScheduledAction")) {
            Resource target = RequestResourceMembers.table().operations().get("application-autoscaling:" + operation)
                    .stream().filter(resource -> resource.arn() == null).findFirst().orElseThrow();
            assertEquals(triple, target.find(), operation);
            assertTrue(target.fill().isEmpty(), operation);
        }
        String formats = "\"arnFormats\":{\"a\":{\"target\":\"arn:${Partition}:a:${Region}:${Account}:target/${Id}\"}}";
        assertEquals(List.of(new Member("Namespace", false), new Member("Dimension", false)), parseUnchecked(document(
                formats, "{\"type\":\"target\",\"fill\":{},\"find\":[\"Namespace\",\"Dimension\"]}"))
                .operations().get("a:Op").getFirst().find());
        // No members, a list, members beside one filling it, no list of them, or beside members reading it otherwise.
        for (String bad : List.of("\"fill\":{},\"find\":[]", "\"fill\":{},\"find\":[\"Ids[]\"]",
                "\"fill\":{\"Id\":\"Id\"},\"find\":[\"Namespace\"]", "\"fill\":{},\"find\":\"Namespace\"",
                "\"fill\":{},\"find\":{\"a\":\"Namespace\"}",
                "\"fill\":{},\"find\":[\"Namespace\"],\"absent\":{\"Namespace\":\"ecs\"}")) {
            assertThrows(IllegalStateException.class,
                    () -> parse(document(formats, "{\"type\":\"target\"," + bad + "}")), bad);
        }
        // The account its member gives is the one it is found in.
        assertEquals(new Member("registryId", false), parseUnchecked(document(formats,
                "{\"type\":\"target\",\"fill\":{},\"find\":[\"Namespace\"],\"account\":\"registryId\"}"))
                .operations().get("a:Op").getFirst().account());
        // Nor beside an ARN member, which names the resource itself.
        assertThrows(IllegalStateException.class, () -> parse(document(formats,
                "{\"types\":[\"target\"],\"arn\":\"TargetArn\",\"find\":[\"Namespace\"]}")));
    }

    @Test
    void aDataApiSessionNamesTheClusterOrWorkgroupItRunsOnThroughItsSessionId() {
        Resource session = new Resource(List.of("cluster", "workgroup"), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null, null, Map.of(), false, List.of(), null, new Member("SessionId", false));
        for (String operation : List.of("ExecuteStatement", "BatchExecuteStatement")) {
            assertEquals(session, RequestResourceMembers.table().operations().get("redshift-data:" + operation)
                    .getLast(), operation);
        }
        assertTrue(RequestResourceMembers.table().operations().get("redshift-data:ListSessions").isEmpty());
        String formats = "\"arnFormats\":{\"a\":{\"cluster\":\"arn:${Partition}:a:${Region}:${Account}:cluster:${Name}\","
                + "\"group\":\"arn:${Partition}:a:${Region}:${Account}:group/${Id}\"}}";
        assertEquals(new Member("SessionId", false), parseUnchecked(document(formats,
                "{\"types\":[\"cluster\",\"group\"],\"through\":\"SessionId\",\"endpoint\":\"a\"}"))
                .operations().get("a:Op").getFirst().through());
        // A list's structures' field names one through each of its values, when another member is absent.
        Resource each = parseUnchecked(document(formats, "{\"types\":[\"group\"],\"through\":\"Parts[].PartId\","
                + "\"when\":{\"GroupId\":[null]}}")).operations().get("a:Op").getFirst();
        assertEquals(new Member("Parts", true, new Member("PartId", false)), each.through());
        assertEquals(Map.of("GroupId", List.of("")), each.when());
        // No types, a type with no format, no member, or beside members reading it otherwise.
        for (String bad : List.of("\"types\":[],\"through\":\"SessionId\"",
                "\"types\":[\"pool\"],\"through\":\"SessionId\"", "\"types\":[\"group\"],\"through\":\"Ids[][]\"",
                "\"types\":[\"group\"],\"through\":\"Session.Id.Part\"",
                "\"types\":[\"group\"],\"through\":\"SessionId\",\"unless\":{\"GroupId\":[null]}",
                "\"types\":[\"group\"],\"through\":\"SessionId\",\"when\":{\"Ids[]\":[\"a\"]}",
                "\"types\":[\"group\"],\"through\":\"SessionId\",\"arn\":\"GroupArn\"",
                "\"types\":[\"group\"],\"through\":\"SessionId\",\"before\":\"#\"",
                "\"type\":\"group\",\"fill\":{},\"through\":\"SessionId\"")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats, "{" + bad + "}")), bad);
        }
    }

    @Test
    void aMalformedTableFailsToLoad() {
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{}}"));
        // An operation of a namespace the file does not read.
        assertThrows(IllegalStateException.class, () -> parse("{\"namespaces\":{\"a\":[]},\"arnFormats\":{},"
                + "\"operations\":{\"b:Op\":{\"resources\":[]}}}"));
        // A type with no format, a variable the format does not have, a member that is no member.
        String formats = "\"arnFormats\":{\"a\":{\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/${ThingName}\"}}";
        assertThrows(IllegalStateException.class, () -> parse(document(formats, "{\"types\":[\"other\"],\"arn\":\"Arn\"}")));
        assertThrows(IllegalStateException.class, () -> parse(document(formats, "{\"type\":\"thing\",\"fill\":{\"Other\":\"Name\"}}")));
        assertThrows(IllegalStateException.class, () -> parse(document(formats, "{\"types\":[\"thing\"],\"arn\":\"a.b.c\"}")));
        assertThrows(IllegalStateException.class, () -> parse(
                "{\"namespaces\":{\"a\":[]},\"arnFormats\":{\"a\":{\"thing\":\"*\"}},\"operations\":{\"a:Op\":{\"resources\":[]}}}"));
        // A type made under a parent whose format it does not extend by one id.
        for (String part : List.of("part/${PartName}", "part/${ThingName}/${A}/${B}", "thing/${ThingName}/${PartId}")) {
            assertThrows(IllegalStateException.class, () -> parse("{\"namespaces\":{\"a\":[]},\"arnFormats\":{\"a\":{"
                    + "\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/${ThingName}\","
                    + "\"part\":\"arn:${Partition}:a:${Region}:${Account}:" + part + "\"}},\"operations\":{\"a:Op\":"
                    + "{\"resources\":[],\"creates\":[{\"type\":\"part\",\"parent\":\"thing\",\"arn\":\"ThingArn\"}]}}}"),
                    part);
        }
        // A member past one structure, or filling a variable from a list in a list.
        assertThrows(IllegalStateException.class, () -> parse(document(formats, "{\"types\":[\"thing\"],\"arn\":\"A[].B.ThingArn\"}")));
        for (String bad : List.of("A.Names[]", "A[].Names[]", "A[].B.Name")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"" + bad + "\"}}")), bad);
        }
        // A field of each of a list's structures fills a variable, read as given when absent or as an ARN, which
        // only a member filling a variable is.
        Member each = new Member("A", true, new Member("Name", false));
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", each), Map.of(), Map.of(),
                Map.of("A[].Name", "default"), Map.of("A[].Name", "thing"), null, null, Map.of(), false, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"A[].Name\"},"
                        + "\"absent\":{\"A[].Name\":\"default\"},\"arnOf\":{\"A[].Name\":\"thing\"}}"))
                        .operations().get("a:Op"));
        assertEquals("A[].Name", each.path());
        for (String bad : List.of("\"absent\":{\"A[].Name\":\"default\"}", "\"arnOf\":{\"A[].Name\":\"thing\"}")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"}," + bad + "}")), bad);
        }
        // A field of a structure fills a variable, as one value.
        Member field = new Member("A", false, new Member("Name", false));
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", field), Map.of(), Map.of(),
                Map.of("A.Name", "default"), Map.of(), null, null, Map.of(), false, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"A.Name\"},"
                        + "\"absent\":{\"A.Name\":\"default\"}}")).operations().get("a:Op"));
        assertEquals("A.Name", field.path());
        // A variable fixed to the one value its handler uses, and one fixed that is no variable, that a member fills,
        // or to no value.
        String owned = "\"arnFormats\":{\"a\":{\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/${Owner}/${ThingName}\"}}";
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", new Member("Name", false)), Map.of(),
                Map.of(), Map.of(), Map.of(), null, null, Map.of("Owner", "Custom"), false, null)),
                parseUnchecked(document(owned, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},"
                        + "\"fixed\":{\"Owner\":\"Custom\"}}")).operations().get("a:Op"));
        for (String bad : List.of("{\"Other\":\"Custom\"}", "{\"ThingName\":\"Custom\"}", "{\"Owner\":\"\"}",
                "{\"Owner\":1}")) {
            assertThrows(IllegalStateException.class, () -> parse(document(owned,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},\"fixed\":" + bad + "}")), bad);
        }
        // The resource a create makes, beside others it names, marked made, and a mark that is not true.
        assertTrue(parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},"
                + "\"made\":true}")).operations().get("a:Op").getFirst().made());
        assertTrue(parseUnchecked(document(formats, "{\"types\":[\"thing\"],\"arn\":\"ThingArn\",\"made\":true}"))
                .operations().get("a:Op").getFirst().made());
        for (String bad : List.of("false", "\"true\"", "1")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},\"made\":" + bad + "}")), bad);
        }
        assertEquals(List.of(new Resource(List.of("thing"), new Member("A", true, new Member("ThingArns", true)), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), null, null, Map.of(), false, null)), parseUnchecked(document(formats, "{\"types\":[\"thing\"],\"arn\":\"A[].ThingArns[]\"}"))
                .operations().get("a:Op"));
        // A member read before more than one character, holding an ARN or filling a variable.
        assertThrows(IllegalStateException.class, () -> parse(document(formats,
                "{\"types\":[\"thing\"],\"arn\":\"ThingArn\",\"before\":\"##\"}")));
        assertThrows(IllegalStateException.class, () -> parse(document(formats,
                "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Id\"},\"before\":\"##\"}")));
        // A member filling a variable read up to a character, as a CodeBuild build id names its project.
        assertEquals(":", parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Id\"},"
                + "\"before\":\":\"}")).operations().get("a:Op").getFirst().before());
        // An account from a list, or for a type whose format has no account.
        assertThrows(IllegalStateException.class, () -> parse(document(formats,
                "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},\"account\":\"Ids[]\"}")));
        assertThrows(IllegalStateException.class, () -> parse(document(
                "\"arnFormats\":{\"a\":{\"thing\":\"arn:${Partition}:a:::thing/${ThingName}\"}}",
                "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},\"account\":\"registryId\"}")));
        // A type checked without a qualifier that has no format, or not given as a list.
        assertThrows(IllegalStateException.class, () -> parse("{\"namespaces\":{\"a\":[]}," + formats
                + ",\"unqualified\":{\"a\":[\"other\"]},\"operations\":{\"a:Op\":{\"resources\":[]}}}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"namespaces\":{\"a\":[]}," + formats
                + ",\"unqualified\":{\"a\":\"thing\"},\"operations\":{\"a:Op\":{\"resources\":[]}}}"));
        // A type given a minted id that has no format, whose format does not end in its one variable, or not a list.
        String parts = "\"arnFormats\":{\"a\":{\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/${ThingName}\","
                + "\"part\":\"arn:${Partition}:a:${Region}:${Account}:part/${ThingName}/${PartId}\"}}";
        for (String bad : List.of("{\"a\":[\"other\"]}", "{\"a\":[\"part\"]}", "{\"a\":\"thing\"}")) {
            assertThrows(IllegalStateException.class, () -> parse("{\"namespaces\":{\"a\":[]}," + parts
                    + ",\"mintedIds\":" + bad + ",\"operations\":{\"a:Op\":{\"resources\":[]}}}"), bad);
        }
        assertTrue(parseUnchecked("{\"namespaces\":{\"a\":[]}," + parts + ",\"mintedIds\":{\"a\":[\"thing\"]},"
                + "\"operations\":{\"a:Op\":{\"resources\":[]}}}").formats().get("a").get("thing").minted());
        // A member read when absent as no value, or as an ARN of a type with no format or of more than one variable.
        for (String bad : List.of("\"absent\":{\"Bus\":\"\"}", "\"absent\":{\"Bus[]\":\"default\"}",
                "\"arnOf\":{\"Bus\":\"other\"}", "\"arnOf\":{\"Bus\":\"part\"}", "\"unless\":{\"Bus\":[]}")) {
            assertThrows(IllegalStateException.class, () -> parse(document("\"arnFormats\":{\"a\":{"
                    + "\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/${ThingName}\","
                    + "\"part\":\"arn:${Partition}:a:${Region}:${Account}:part/${ThingName}/${PartId}\"}}",
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"}," + bad + "}")), bad);
        }
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", new Member("Name", false)),
                Map.of(), Map.of("Bus", List.of("default")), Map.of("Bus", "default"), Map.of("Bus", "thing"), null, null,
                Map.of(), false, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},"
                        + "\"unless\":{\"Bus\":[\"default\"]},\"absent\":{\"Bus\":\"default\"},"
                        + "\"arnOf\":{\"Bus\":\"thing\"}}")).operations().get("a:Op"));
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", new Member("Names", true)), Map.of(),
                        Map.of(), Map.of(), Map.of(), new Member("registryId", false), null, Map.of(), false, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Names[]\"},"
                        + "\"account\":\"registryId\"}")).operations().get("a:Op"));
    }

    /**
     * Each resource alone, from a request naming it and nothing else, gives exactly its ARN, in the account its
     * account member names when it has one, with those sharing its explicit member selector, those the operation
     * always names, such as a create's new resource (Cloud Map's {@code CreateService}), those filled from the ARN
     * it is read from (a permission set made in an instance), and those a member it shares fills, their other
     * variables open (Redshift's {@code DeleteClusterSnapshot} names {@code cluster:<id>} by the
     * {@code SnapshotClusterIdentifier} that also fills {@code snapshot:<id>/<name>}); one named through another
     * resource of its service's own, the ARN its service gives for that. A member the table locates in a REST
     * request's path or query is given there, and a body or a form giving it another value names nothing.
     */
    @TestFactory
    Stream<DynamicTest> everyResourceOfTheTableIsReadFromAJsonBodyAndAForm() {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        return table.operations().entrySet().stream().flatMap(operation -> operation.getValue().stream()
                .map(resource -> DynamicTest.dynamicTest(operation.getKey() + " " + resource, () -> {
                    String namespace = operation.getKey().substring(0, operation.getKey().indexOf(':'));
                    String name = operation.getKey().substring(namespace.length() + 1);
                    ArnFormat format = table.formats().get(namespace).get(resource.types().getFirst());
                    String arn = example(format.format());
                    String account = resource.account() != null ? "111122223333" : "000000000000";
                    String expected = resource.arn() != null || resource.through() != null ? arn
                            : expected(format.format(), resource, account) + (format.minted() ? "/*" : "");
                    ObjectNode body = MAPPER.createObjectNode();
                    StringBuilder form = new StringBuilder("Action=" + name);
                    Map<Member, String> values = resource.arn() != null ? Map.of(resource.arn(), arn)
                            : resource.through() != null ? Map.of(resource.through(), "x")
                            : Stream.concat(resource.fill().values().stream(), resource.find().stream()).distinct()
                                    .collect(Collectors.toMap(m -> m, m -> "x"));
                    Map<Member, String> given = new LinkedHashMap<>(values);
                    if (resource.account() != null) {
                        given.put(resource.account(), account);
                    }
                    resource.when().forEach((member, allowed) -> {
                        if (!allowed.getFirst().isEmpty()) {
                            given.put(new Member(member, false), allowed.getFirst());
                        }
                    });
                    // A member the table locates in a REST request's path or query goes there, where the reader takes it
                    // from, and the body and the form give it another value, which names nothing.
                    // REST map selectors have no Query form encoding, so they are checked only as JSON.
                    Map<String, String> located = table.located().getOrDefault(operation.getKey(), Map.of());
                    Map<String, String> labels = new LinkedHashMap<>();
                    MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
                    given.forEach((member, value) -> {
                        String where = member.field() == null && !member.list() ? located.get(member.name()) : null;
                        if (where != null && where.startsWith("{")) {
                            labels.put(where.substring(1, where.length() - 1), value);
                        } else if (where != null) {
                            query.add(where.substring(1), value);
                        }
                        String sent = where == null ? value : "other";
                        put(body, member, sent);
                        form.append('&').append(parameter(member)).append('=').append(encoded(sent));
                    });
                    List<String> named = operation.getValue().stream()
                            .filter(other -> (other == resource || named(other) || sharesAMember(other, given)
                                    || fillsFromTheArn(other, resource)
                                    || !other.find().isEmpty() && other.find().equals(resource.find())
                                    && other.when().equals(resource.when()) && other.unless().equals(resource.unless()))
                                    && (other.endpoint() == null || resource.endpoint() == null
                                    || other.endpoint().equals(resource.endpoint())))
                            .map(other -> other == resource ? expected : expected(
                                    table.formats().get(namespace).get(other.types().getFirst()).format(), other,
                                    "000000000000", given.keySet()))
                            .distinct().toList();
                    StoredResourceArns stored = runningOn(namespace, arn);
                    assertEquals(named, RequestResources.of(operation.getKey(),
                            sentWith(servedBy(json(body.toString()), resource.endpoint()), query), "aws", "us-east-1",
                            "000000000000", stored, labels), body + " " + labels + " " + query);
                    if (given.keySet().stream().noneMatch(Member::map)) {
                        assertEquals(named, RequestResources.of(operation.getKey(),
                                sentWith(servedBy(form(form.toString()), resource.endpoint()), query), "aws", "us-east-1",
                                "000000000000", stored, labels), form + " " + labels + " " + query);
                    }
                })));
    }

    /** Each resource a create makes under a parent, from a request naming the parent's ARN, is the parent's own. */
    @TestFactory
    Stream<DynamicTest> everyResourceACreateMakesIsNamedFromItsParentsArn() {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        return table.creates().entrySet().stream().flatMap(operation -> operation.getValue().stream()
                .map(created -> DynamicTest.dynamicTest(operation.getKey() + " " + created.type(), () -> {
                    String namespace = operation.getKey().substring(0, operation.getKey().indexOf(':'));
                    String name = operation.getKey().substring(namespace.length() + 1);
                    String parent = example(table.formats().get(namespace).get(created.parent()).format());
                    String child = example(table.formats().get(namespace).get(created.type()).format());
                    String expected = child.substring(0, child.length() - 1) + "*";
                    ObjectNode body = MAPPER.createObjectNode();
                    if (created.arn().list()) {
                        body.putArray(created.arn().name()).add(parent);
                    } else {
                        body.put(created.arn().name(), parent);
                    }
                    String form = "Action=" + name + "&" + created.arn().name() + (created.arn().list() ? ".member.1=" : "=")
                            + encoded(parent);
                    assertTrue(RequestResources.created(operation.getKey(), json(body.toString()), "aws",
                            "us-east-1", "000000000000").contains(expected), body.toString());
                    assertTrue(RequestResources.created(operation.getKey(), form(form), "aws", "us-east-1",
                            "000000000000").contains(expected), form);
                })));
    }

    /**
     * Whether a resource fills its variables from the ARN the other's member holds, as that ARN's type's variable alone
     * (a permission set made in the instance an {@code InstanceArn} names), so a request naming the other by an ARN of
     * its first type, which the test gives, names it too.
     */
    private static boolean fillsFromTheArn(Resource resource, Resource other) {
        return other.arn() != null && !resource.fill().isEmpty()
                && resource.fill().values().stream().allMatch(other.arn()::equals)
                && other.types().getFirst().equals(resource.arnOf().get(other.arn().path()));
    }

    /** Whether every request of its operation names the resource: no member names it and no member's value selects it. */
    private static boolean named(Resource resource) {
        return resource.arn() == null && resource.fill().isEmpty() && resource.find().isEmpty()
                && resource.through() == null && resource.when().isEmpty() && resource.unless().isEmpty();
    }

    /** A namespace's stored ARNs that find nothing, and give {@code arn} for whatever a request names a resource through. */
    private static StoredResourceArns runningOn(String namespace, String arn) {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public List<String> through(String member, String value, String region) {
                return List.of(arn);
            }
        };
    }

    /**
     * Whether a request giving these members names the resource too: it fills a variable from one of them, not from
     * the ARN one holds ({@link #fillsFromTheArn} decides those), and no {@code when} or {@code unless} the request's
     * values fail leaves it out (each member given is {@code x}, any other absent, which a value list holds as
     * {@code ""}).
     */
    private static boolean sharesAMember(Resource resource, Map<Member, String> given) {
        if (resource.arn() != null || resource.fill().values().stream()
                .noneMatch(member -> given.containsKey(member) && (!resource.arnOf().containsKey(member.path())
                        || !AwsArnUtils.isArn(given.get(member))))) {
            return false;
        }
        Map<String, String> values = given.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue, (a, b) -> a));
        return resource.when().entrySet().stream()
                .allMatch(when -> when.getValue().contains(values.getOrDefault(when.getKey(), "")))
                && resource.unless().entrySet().stream()
                .noneMatch(unless -> unless.getValue().contains(values.getOrDefault(unless.getKey(), "")));
    }

    /** The value at the member's path in a body: in its list's first element, and in its field's structure. */
    private static void put(ObjectNode body, Member member, String value) {
        if (member.field() == null) {
            if (member.list()) {
                body.putArray(member.name()).add(value);
            } else {
                body.put(member.name(), value);
            }
        } else if (member.map()) {
            put(body.putObject(member.name()).putObject("table"), member.field(), value);
        } else {
            put(member.list() ? body.putArray(member.name()).addObject() : body.putObject(member.name()), member.field(), value);
        }
    }

    /** The form parameter of the member's path, its lists' first elements. */
    private static String parameter(Member member) {
        String name = member.name() + (member.list() ? ".member.1" : "");
        return member.field() == null ? name : name + "." + parameter(member.field());
    }

    /** An ARN of the format in the test's partition, Region and account, each other variable {@code x}. */
    private static String example(String format) {
        return format.replace("${Partition}", "aws").replace("${Region}", "us-east-1")
                .replace("${Account}", "000000000000").replaceAll("\\$\\{\\w+}", "x");
    }

    /** The format in the account with each variable the resource fills {@code x}, and each other open. */
    private static String expected(String format, Resource resource, String account) {
        return expected(format, resource, account, resource.fill().values());
    }

    /** The format in the account with each variable the resource fills from one of {@code given} {@code x}. */
    private static String expected(String format, Resource resource, String account, Collection<Member> given) {
        String arn = format.replace("${Partition}", "aws").replace("${Region}", "us-east-1")
                .replace("${Account}", account);
        for (Map.Entry<String, Member> variable : resource.fill().entrySet()) {
            if (given.contains(variable.getValue())) {
                arn = arn.replace("${" + variable.getKey() + "}", "x");
            }
        }
        for (Map.Entry<String, String> variable : resource.fixed().entrySet()) {
            arn = arn.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return arn.replaceAll("\\$\\{\\w+}", "*");
    }

    private static ArnFormat format(String format) {
        return new ArnFormat(format, RequestResourceMembers.pattern(format), false);
    }

    private static String document(String formats, String resource) {
        return "{\"namespaces\":{\"a\":[]}," + formats + ",\"operations\":{\"a:Op\":{\"resources\":[" + resource + "]}}}";
    }

    private static RequestResourceMembers.Table parse(String document) throws Exception {
        return RequestResourceMembers.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    private static RequestResourceMembers.Table parseUnchecked(String document) {
        try {
            return parse(document);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The request as the service of the endpoint prefix serves it; as no known service when there is none. */
    static ContainerRequestContext servedBy(ContainerRequestContext request, String endpoint) {
        if (endpoint != null) {
            ServiceDescriptor service = new ServiceDescriptor(endpoint, endpoint, true, true, null, null, 0L, null,
                    ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of());
            when(request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY))
                    .thenReturn(new ProtocolClaim(WireProtocol.AWS_JSON_1_1, service, null, null));
        }
        return request;
    }

    /** The request with the query it is sent with. */
    private static ContainerRequestContext sentWith(ContainerRequestContext request,
                                                    MultivaluedMap<String, String> query) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(request.getUriInfo()).thenReturn(uriInfo);
        return request;
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body);
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body);
    }

    private static ContainerRequestContext request(String mediaType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
