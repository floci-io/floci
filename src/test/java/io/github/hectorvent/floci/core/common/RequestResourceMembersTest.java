package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RequestResourceMembers.ArnFormat;
import io.github.hectorvent.floci.core.common.RequestResourceMembers.Member;
import io.github.hectorvent.floci.core.common.RequestResourceMembers.Resource;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void theTableReadsItsNamespacesAndGivesEachTypeItNamesAnArnFormat() {
        RequestResourceMembers.Table table = RequestResourceMembers.table();
        assertTrue(table.namespaces().containsAll(List.of("cognito-idp", "ecr", "elasticloadbalancing", "states")));
        assertTrue(table.handBuilt().contains("lambda"));
        assertTrue(table.namespaces().stream().noneMatch(table.handBuilt()::contains));
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
        // A member past one structure, or filling a variable from a lone structure's member or a list in a list.
        assertThrows(IllegalStateException.class, () -> parse(document(formats, "{\"types\":[\"thing\"],\"arn\":\"A[].B.ThingArn\"}")));
        for (String bad : List.of("A.Name", "A[].Names[]", "A[].B.Name")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"" + bad + "\"}}")), bad);
        }
        // A field of each of a list's structures fills a variable, read as given when absent or as an ARN, which
        // only a member filling a variable is.
        Member each = new Member("A", true, new Member("Name", false));
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", each), Map.of(), Map.of(),
                Map.of("A[].Name", "default"), Map.of("A[].Name", "thing"), null, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"A[].Name\"},"
                        + "\"absent\":{\"A[].Name\":\"default\"},\"arnOf\":{\"A[].Name\":\"thing\"}}"))
                        .operations().get("a:Op"));
        assertEquals("A[].Name", each.path());
        for (String bad : List.of("\"absent\":{\"A[].Name\":\"default\"}", "\"arnOf\":{\"A[].Name\":\"thing\"}")) {
            assertThrows(IllegalStateException.class, () -> parse(document(formats,
                    "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"}," + bad + "}")), bad);
        }
        assertEquals(List.of(new Resource(List.of("thing"), new Member("A", true, new Member("ThingArns", true)), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), null, null)), parseUnchecked(document(formats, "{\"types\":[\"thing\"],\"arn\":\"A[].ThingArns[]\"}"))
                .operations().get("a:Op"));
        // An ARN read before more than one character.
        assertThrows(IllegalStateException.class, () -> parse(document(formats,
                "{\"types\":[\"thing\"],\"arn\":\"ThingArn\",\"before\":\"##\"}")));
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
                Map.of(), Map.of("Bus", List.of("default")), Map.of("Bus", "default"), Map.of("Bus", "thing"), null, null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Name\"},"
                        + "\"unless\":{\"Bus\":[\"default\"]},\"absent\":{\"Bus\":\"default\"},"
                        + "\"arnOf\":{\"Bus\":\"thing\"}}")).operations().get("a:Op"));
        assertEquals(List.of(new Resource(List.of("thing"), null, Map.of("ThingName", new Member("Names", true)), Map.of(),
                        Map.of(), Map.of(), Map.of(), new Member("registryId", false), null)),
                parseUnchecked(document(formats, "{\"type\":\"thing\",\"fill\":{\"ThingName\":\"Names[]\"},"
                        + "\"account\":\"registryId\"}")).operations().get("a:Op"));
    }

    /**
     * Each resource alone, from a request naming it and nothing else, gives exactly its ARN, in the account its
     * account member names when it has one.
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
                    String expected = resource.arn() != null ? arn
                            : expected(format.format(), resource, account) + (format.minted() ? "/*" : "");
                    ObjectNode body = MAPPER.createObjectNode();
                    StringBuilder form = new StringBuilder("Action=" + name);
                    Map<Member, String> values = resource.arn() != null ? Map.of(resource.arn(), arn)
                            : resource.fill().values().stream().distinct().collect(Collectors.toMap(m -> m, m -> "x"));
                    Map<Member, String> given = new LinkedHashMap<>(values);
                    if (resource.account() != null) {
                        given.put(resource.account(), account);
                    }
                    resource.when().forEach((member, allowed) -> {
                        if (!allowed.getFirst().isEmpty()) {
                            given.put(new Member(member, false), allowed.getFirst());
                        }
                    });
                    given.forEach((member, value) -> {
                        put(body, member, value);
                        form.append('&').append(parameter(member)).append('=').append(encoded(value));
                    });
                    assertEquals(List.of(expected), RequestResources.of(operation.getKey(), json(body.toString()),
                            "aws", "us-east-1", "000000000000"), body.toString());
                    assertEquals(List.of(expected), RequestResources.of(operation.getKey(), form(form.toString()),
                            "aws", "us-east-1", "000000000000"), form.toString());
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

    /** The value at the member's path in a body: in its list's first element, and in its field's structure. */
    private static void put(ObjectNode body, Member member, String value) {
        if (member.field() == null) {
            if (member.list()) {
                body.putArray(member.name()).add(value);
            } else {
                body.put(member.name(), value);
            }
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
        String arn = format.replace("${Partition}", "aws").replace("${Region}", "us-east-1")
                .replace("${Account}", account);
        for (String variable : resource.fill().keySet()) {
            arn = arn.replace("${" + variable + "}", "x");
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
