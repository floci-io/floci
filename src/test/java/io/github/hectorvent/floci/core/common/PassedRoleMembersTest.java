package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.PassedRoleMembers.Into;
import io.github.hectorvent.floci.core.common.PassedRoleMembers.Member;
import io.github.hectorvent.floci.core.common.PassedRoleMembers.Step;
import io.github.hectorvent.floci.core.common.PassedRoles.PassedRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The vendored table of where each operation's request names the roles it passes, and that
 * {@link PassedRoles} reads every member it gives, from a JSON body and from Query form parameters.
 */
class PassedRoleMembersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::111122223333:role/passed";
    private static final String NAMED = "named-role";
    private static final String HELD = "arn:aws:iam::111122223333:role/held/named-role";

    /** IAM holds {@code named-role} under the path {@code /held/}; nothing else is there to look up. */
    private static final PassedRoles.Lookups LOOKUPS = new PassedRoles.Lookups() {
        @Override
        public List<String> instanceProfileRoleArns(String profileName, String profileArn) {
            return List.of();
        }

        @Override
        public List<String> taskDefinitionRoles(String reference) {
            return List.of();
        }

        @Override
        public Optional<String> stackSetPermissionModel(String stackSetName) {
            return Optional.empty();
        }

        @Override
        public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version) {
            return Optional.empty();
        }

        @Override
        public Optional<String> launchConfigurationInstanceProfile(String instanceId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId,
                                                               String templateName, String version) {
            return Optional.empty();
        }

        @Override
        public String roleArn(String roleName) {
            return "arn:aws:iam::111122223333:role/" + roleName;
        }

        @Override
        public Optional<String> heldRoleArn(String roleName) {
            return NAMED.equals(roleName) ? Optional.of(HELD) : Optional.empty();
        }

        @Override
        public Optional<String> serviceCatalogConstraintType(String constraintId) {
            return Optional.empty();
        }
    };

    @Test
    void theTableReadsTheOperationsOfEveryNamespaceItCoversOnce() {
        PassedRoleMembers.Table table = PassedRoleMembers.table();
        assertFalse(table.members().isEmpty());
        for (String operation : table.members().keySet()) {
            assertFalse(table.byHand().contains(operation) || table.notRead().contains(operation), operation);
            assertTrue(table.namespaces().contains(operation.substring(0, operation.indexOf(':'))), operation);
        }
        assertFalse(table.byHand().stream().anyMatch(table.notRead()::contains));
        assertTrue(table.otherNamespaces().stream().noneMatch(table.namespaces()::contains));
        assertTrue(table.notCovered().stream().noneMatch(table.namespaces()::contains));
    }

    @Test
    void aMemberPathIsReadAsItsSteps() {
        Member list = member("Rule.Actions[].S3Action.IamRoleArn", "ses");
        assertEquals(List.of(new Step("Rule", Into.NONE, null), new Step("Actions", Into.LIST, ""),
                new Step("S3Action", Into.NONE, null), new Step("IamRoleArn", Into.NONE, null)), list.steps());
        assertTrue(list.queryKeys().matcher("Rule.Actions.member.2.S3Action.IamRoleArn").matches());
        assertTrue(list.queryKeys().matcher("Rule.Actions.2.S3Action.IamRoleArn").matches());
        assertFalse(list.queryKeys().matcher("Rule.Actions.S3Action.IamRoleArn").matches());

        Member named = member("AssociatedRoles[DBClusterAssociatedRole].RoleArn", "rds");
        assertTrue(named.queryKeys().matcher("AssociatedRoles.DBClusterAssociatedRole.1.RoleArn").matches());
        assertTrue(named.queryKeys().matcher("AssociatedRoles.member.1.RoleArn").matches());
        assertFalse(named.queryKeys().matcher("AssociatedRoles.Other.1.RoleArn").matches());

        assertTrue(member("Roles{}", null).queryKeys().matcher("Roles.entry.3.value").matches());
        // One key of a map cannot be told apart in a form, whose keys and values are separate parameters.
        Member key = member("Definition{AssumeRole}", "servicecatalog");
        assertEquals(new Step("Definition", Into.KEY, "AssumeRole"), key.steps().get(0));
        assertNull(key.queryKeys());
    }

    @Test
    void aMalformedTableFailsToLoad() {
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{}}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{\"a:B\":{\"passedTo\":[]}}}"));
        assertThrows(IllegalStateException.class, () -> parse(
                "{\"operations\":{\"a:B\":{\"passedTo\":[],\"roles\":[{\"member\":\"A..B\"}]}}}"));
    }

    /**
     * Every operation the table says is read by hand has a case in {@link PassedRoles#byHand}, and no
     * other operation of the table does, so neither shadows the other.
     */
    @Test
    void exactlyTheOperationsReadByHandHaveACaseOfTheirOwn() {
        PassedRoleMembers.Table table = PassedRoleMembers.table();
        for (String operation : table.byHand()) {
            assertNotNull(PassedRoles.byHand(operation, json("{}"), LOOKUPS), operation + " has no case of its own");
        }
        Stream.concat(table.members().keySet().stream(), table.notRead().stream()).forEach(operation ->
                assertNull(PassedRoles.byHand(operation, json("{}"), LOOKUPS),
                        operation + " is read by hand; say so in the generator's BY_HAND"));
    }

    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableIsReadFromAJsonBody() {
        return PassedRoleMembers.table().members().entrySet().stream()
                .flatMap(operation -> operation.getValue().stream().map(member -> DynamicTest.dynamicTest(
                        operation.getKey() + " " + member.path(), () -> assertEquals(List.of(expected(member)),
                                PassedRoles.of(operation.getKey(), json(body(member).toString()), LOOKUPS)))));
    }

    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableAQueryRequestCanCarryIsReadFromItsForm() {
        return PassedRoleMembers.table().members().entrySet().stream()
                .flatMap(operation -> operation.getValue().stream()
                        .filter(member -> member.queryKeys() != null)
                        .map(member -> DynamicTest.dynamicTest(operation.getKey() + " " + member.path(),
                                () -> assertEquals(List.of(expected(member)),
                                        PassedRoles.of(operation.getKey(), form(formKey(member) + "="
                                                + URLEncoder.encode(value(member), StandardCharsets.UTF_8)), LOOKUPS)))));
    }

    @TestFactory
    Stream<DynamicTest> aMemberHoldingSomethingOtherThanARolePassesNothing() {
        return PassedRoleMembers.table().members().entrySet().stream()
                .flatMap(operation -> operation.getValue().stream().map(member -> DynamicTest.dynamicTest(
                        operation.getKey() + " " + member.path(), () -> assertEquals(List.of(),
                                PassedRoles.of(operation.getKey(),
                                        json(body(member, "arn:aws:s3:::bucket").toString()), LOOKUPS)))));
    }

    /** The role a request names at the member: by name where the member takes one, else by ARN. */
    private static String value(Member member) {
        return member.byName() ? NAMED : ROLE;
    }

    private static PassedRole expected(Member member) {
        return new PassedRole(member.byName() ? HELD : ROLE,
                member.passedTo() == null ? null : ServicePrincipals.of(member.passedTo()));
    }

    private static JsonNode body(Member member) {
        return body(member, value(member));
    }

    private static JsonNode body(Member member, String value) {
        JsonNode node = TextNode.valueOf(value);
        List<Step> steps = member.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            JsonNode inner = switch (step.into()) {
                case NONE -> node;
                case LIST -> MAPPER.createArrayNode().add(node);
                case MAP -> MAPPER.createObjectNode().set("any", node);
                case KEY -> MAPPER.createObjectNode().set(step.key(), node);
            };
            ObjectNode outer = MAPPER.createObjectNode();
            outer.set(step.name(), inner);
            node = outer;
        }
        return node;
    }

    /** The member's form key, a list's first element in the generic form and a map's first value. */
    private static String formKey(Member member) {
        List<String> parts = new ArrayList<>();
        for (Step step : member.steps()) {
            parts.add(step.name());
            if (step.into() == Into.LIST) {
                parts.add("member.1");
            } else if (step.into() == Into.MAP) {
                parts.add("entry.1.value");
            }
        }
        return String.join(".", parts);
    }

    private static Member member(String path, String passedTo) {
        String role = passedTo == null ? "{\"member\":\"" + path + "\"}"
                : "{\"member\":\"" + path + "\",\"passedTo\":\"" + passedTo + "\"}";
        try {
            return parse("{\"operations\":{\"a:B\":{\"passedTo\":[],\"roles\":[" + role + "]}}}")
                    .members().get("a:B").get(0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static PassedRoleMembers.Table parse(String document) throws Exception {
        return PassedRoleMembers.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
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
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
