package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::EC2::KeyPair} through real stack operations. A created key, of the type and in the
 * format the template asks for, keeps its private key in the {@code SecureString} parameter
 * {@code /ec2/keypair/<KeyPairId>} and an imported one writes none; {@code Ref} and
 * {@code Fn::GetAtt} report the key DescribeKeyPairs describes; a rename replaces the key, and a
 * change to another property under the same name is refused, as every property is create-only; and
 * the stack delete, a create rollback, a committed rename and a rolled-back rename each take a key's
 * parameter with it. A failing operation ends in a Secrets Manager secret that cannot be created.
 */
@QuarkusTest
class CloudFormationEc2KeyPairIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    /** The public key of the import example in the {@code AWS::EC2::KeyPair} reference. */
    private static final String ED25519_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICfp1F7DhdWZdqkYAUGCzcBsLmJeu9izpIyGpmmg7eCz example";

    @Inject
    Ec2Service ec2Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** One {@code AWS::EC2::KeyPair}; a null property is left out of the template. */
    private record Key(String name, String keyType, String keyFormat, String publicKeyMaterial,
                       Map<String, String> tags) {
        static Key named(String name) {
            return new Key(name, null, null, null, Map.of());
        }
    }

    @ParameterizedTest
    @CsvSource({
        ",,rsa,-----BEGIN RSA PRIVATE KEY-----",
        "ed25519,,ed25519,-----BEGIN OPENSSH PRIVATE KEY-----",
        "rsa,ppk,rsa,PuTTY-User-Key-File-2: ssh-rsa",
        "ed25519,ppk,ed25519,PuTTY-User-Key-File-2: ssh-ed25519"
    })
    void aCreatedKeyKeepsItsPrivateKeyInASecureStringParameterUntilTheStackIsDeleted(
            String keyType, String keyFormat, String describedType, String firstLine) throws Exception {
        String stack = unique("cfn-key-create");
        String name = stack + "-key";
        deploy("CreateStack", stack, template(new Key(name, keyType, keyFormat, null, Map.of("team", "infra")), false));
        assertStatus(stack, "CREATE_COMPLETE");

        XmlPath described = describeKeyPair(name);
        String id = described.getString("DescribeKeyPairsResponse.keySet.item.keyPairId");
        assertEquals(Map.of("Name", name, "Id", id,
                        "Fingerprint", described.getString("DescribeKeyPairsResponse.keySet.item.keyFingerprint")),
                outputs(stack));
        assertEquals(describedType, described.getString("DescribeKeyPairsResponse.keySet.item.keyType"));
        assertEquals("team", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.key"));
        assertEquals("infra", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.value"));
        assertEquals(name, physicalId(stack));

        JsonPath parameter = parameter("/ec2/keypair/" + id);
        assertEquals("SecureString", parameter.getString("Parameter.Type"));
        String privateKey = parameter.getString("Parameter.Value");
        // Checked without printing either side: both are private keys.
        assertTrue(privateKey.startsWith(firstLine + "\n"), "the private key is in the format the template asks for");
        assertTrue(privateKey.equals(storedPrivateKey(id)), "the parameter holds the key pair's own private key");

        deleteStack(stack);
        assertKeyPairMissing(name);
        assertParameterMissing("/ec2/keypair/" + id);
    }

    @Test
    void anImportedKeyTakesItsTypeFromTheMaterialAndWritesNoParameter() throws Exception {
        String stack = unique("cfn-key-import");
        String name = stack + "-key";
        // KeyType is ignored when PublicKeyMaterial is given: the material says what the key is.
        deploy("CreateStack", stack, template(new Key(name, "rsa", null, ED25519_PUBLIC_KEY, Map.of()), false));
        assertStatus(stack, "CREATE_COMPLETE");

        XmlPath described = describeKeyPair(name);
        String id = described.getString("DescribeKeyPairsResponse.keySet.item.keyPairId");
        assertEquals("ed25519", described.getString("DescribeKeyPairsResponse.keySet.item.keyType"));
        assertEquals(Map.of("Name", name, "Id", id,
                        "Fingerprint", described.getString("DescribeKeyPairsResponse.keySet.item.keyFingerprint")),
                outputs(stack));
        assertParameterMissing("/ec2/keypair/" + id);

        deleteStack(stack);
        assertKeyPairMissing(name);
    }

    @Test
    void renamingTheKeyReplacesItAndTheCommittedUpdateDeletesTheOldKeyWithItsParameter() throws Exception {
        String stack = unique("cfn-key-rename");
        String first = stack + "-first";
        String next = stack + "-next";
        deploy("CreateStack", stack, template(Key.named(first), false));
        assertStatus(stack, "CREATE_COMPLETE");
        String firstId = keyPairId(first);

        deploy("UpdateStack", stack, template(Key.named(next), false));
        assertStatus(stack, "UPDATE_COMPLETE");

        assertKeyPairMissing(first);
        assertParameterMissing("/ec2/keypair/" + firstId);
        String nextId = keyPairId(next);
        assertEquals("SecureString", parameter("/ec2/keypair/" + nextId).getString("Parameter.Type"));
        assertEquals(next, outputs(stack).get("Name"));
        assertEquals(nextId, outputs(stack).get("Id"));
        assertTrue(events(stack).stream().anyMatch(event -> first.equals(event.get("PhysicalResourceId"))
                        && "DELETE_COMPLETE".equals(event.get("ResourceStatus"))),
                "the update's cleanup reports the displaced key deleted");

        deleteStack(stack);
        assertKeyPairMissing(next);
        assertParameterMissing("/ec2/keypair/" + nextId);
    }

    @Test
    void aFailedUpdateRollsARenameBackAndDeletesTheReplacementWithItsParameter() throws Exception {
        String stack = unique("cfn-key-rollback");
        String first = stack + "-first";
        String next = stack + "-next";
        deploy("CreateStack", stack, template(Key.named(first), false));
        assertStatus(stack, "CREATE_COMPLETE");
        String firstId = keyPairId(first);
        Set<String> parametersBefore = keyPairParameters();

        deploy("UpdateStack", stack, template(Key.named(next), true));
        assertStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertTrue(hasEvent(stack, next, "UPDATE_COMPLETE"), "the failed update created the replacement");
        assertKeyPairMissing(next);
        assertEquals(parametersBefore, keyPairParameters(), "the replacement's parameter is deleted with it");
        assertEquals(firstId, keyPairId(first));
        assertEquals(first, outputs(stack).get("Name"));
        assertEquals(firstId, outputs(stack).get("Id"));
        assertEquals(first, physicalId(stack));

        // Nothing was left under the new name, so the rename can be made again.
        deploy("UpdateStack", stack, template(Key.named(next), false));
        assertStatus(stack, "UPDATE_COMPLETE");
        String nextId = keyPairId(next);
        assertKeyPairMissing(first);
        deleteStack(stack);
        assertKeyPairMissing(next);
        assertParameterMissing("/ec2/keypair/" + nextId);
    }

    @Test
    void changingAnotherPropertyUnderTheSameNameIsRefusedAndKeepsTheKey() throws Exception {
        String stack = unique("cfn-key-same-name");
        String name = stack + "-key";
        deploy("CreateStack", stack, template(Key.named(name), false));
        assertStatus(stack, "CREATE_COMPLETE");
        String id = keyPairId(name);

        deploy("UpdateStack", stack, template(new Key(name, "ed25519", null, null, Map.of()), false));
        assertStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename " + name + " and update the stack again.", CfnStackWaits.failedResourceReason(stack));
        XmlPath described = describeKeyPair(name);
        assertEquals(id, described.getString("DescribeKeyPairsResponse.keySet.item.keyPairId"));
        assertEquals("rsa", described.getString("DescribeKeyPairsResponse.keySet.item.keyType"));
        assertEquals("SecureString", parameter("/ec2/keypair/" + id).getString("Parameter.Type"));

        deleteStack(stack);
        assertKeyPairMissing(name);
        assertParameterMissing("/ec2/keypair/" + id);
    }

    @Test
    void aCreateThatFailsLaterDeletesTheKeyAndItsParameter() throws Exception {
        String stack = unique("cfn-key-create-rollback");
        String name = stack + "-key";
        Set<String> parametersBefore = keyPairParameters();

        deploy("CreateStack", stack, template(Key.named(name), true));
        assertStatus(stack, "ROLLBACK_COMPLETE");

        assertTrue(hasEvent(stack, name, "CREATE_COMPLETE"), "the failed create made the key");
        assertTrue(hasEvent(stack, name, "DELETE_COMPLETE"), "the rollback deleted it");
        assertKeyPairMissing(name);
        assertEquals(parametersBefore, keyPairParameters(), "the key's parameter is deleted with it");
        deleteStack(stack);
    }

    /**
     * Only {@code AWS::NoValue} leaves a property out: an empty string a parameter supplies is a
     * value, which an import refuses as it refuses the same string written literally.
     */
    @Test
    void noValueLeavesPublicKeyMaterialOutButABlankParameterDoesNot() throws Exception {
        String stack = unique("cfn-key-no-value");
        String name = stack + "-key";
        Map<String, Object> material = Map.of("Fn::If", List.of("Import", Map.of("Ref", "Material"),
                Map.of("Ref", "AWS::NoValue")));
        deploy("CreateStack", stack, parameterTemplate(name, material, "false"));
        assertStatus(stack, "CREATE_COMPLETE");
        String id = keyPairId(name);
        assertEquals("SecureString", parameter("/ec2/keypair/" + id).getString("Parameter.Type"));
        deleteStack(stack);
        assertKeyPairMissing(name);

        String blank = unique("cfn-key-blank");
        deploy("CreateStack", blank, parameterTemplate(blank + "-key", Map.of("Ref", "Material"), "true"));
        assertStatus(blank, "ROLLBACK_COMPLETE");
        assertEquals("The request must contain the parameter PublicKeyMaterial",
                CfnStackWaits.failedResourceReason(blank));
        assertKeyPairMissing(blank + "-key");
        deleteStack(blank);
    }

    @Test
    void aNameTakenOutsideTheStackFailsTheCreateAndLeavesThatKeyAlone() throws Exception {
        String stack = unique("cfn-key-taken");
        String name = stack + "-key";
        String outsideId = ec2("CreateKeyPair").formParam("KeyName", name).post("/")
                .then().statusCode(200).extract().xmlPath().getString("CreateKeyPairResponse.keyPairId");
        try {
            deploy("CreateStack", stack, template(Key.named(name), false));
            assertStatus(stack, "ROLLBACK_COMPLETE");

            assertEquals("The keypair '" + name + "' already exists", CfnStackWaits.failedResourceReason(stack));
            assertEquals(outsideId, keyPairId(name));
            deleteStack(stack);
            assertEquals(outsideId, keyPairId(name));
        } finally {
            ec2("DeleteKeyPair").formParam("KeyPairId", outsideId).post("/").then().statusCode(200);
        }
    }

    @Test
    void aChangeSetReportsAChangedNameOrTypeAsAReplacement() throws Exception {
        String stack = unique("cfn-key-change-set");
        String name = stack + "-key";
        deploy("CreateStack", stack, template(new Key(name, "rsa", null, null, Map.of()), false));
        assertStatus(stack, "CREATE_COMPLETE");

        assertEquals("True", replacement(stack, "renamed",
                template(new Key(name + "-next", "rsa", null, null, Map.of()), false)));
        assertEquals("True", replacement(stack, "retyped",
                template(new Key(name, "ed25519", null, null, Map.of()), false)));

        deleteStack(stack);
        assertKeyPairMissing(name);
    }

    private static String template(Key key, boolean failAfterKey) throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("KeyName", key.name());
        if (key.keyType() != null) {
            properties.put("KeyType", key.keyType());
        }
        if (key.keyFormat() != null) {
            properties.put("KeyFormat", key.keyFormat());
        }
        if (key.publicKeyMaterial() != null) {
            properties.put("PublicKeyMaterial", key.publicKeyMaterial());
        }
        if (!key.tags().isEmpty()) {
            properties.put("Tags", key.tags().entrySet().stream()
                    .map(tag -> Map.of("Key", tag.getKey(), "Value", tag.getValue())).toList());
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Key", Map.of("Type", "AWS::EC2::KeyPair", "Properties", properties));
        if (failAfterKey) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", List.of("Key"),
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        Map<String, Object> outputs = Map.of(
                "Name", Map.of("Value", Map.of("Ref", "Key")),
                "Id", Map.of("Value", Map.of("Fn::GetAtt", List.of("Key", "KeyPairId"))),
                "Fingerprint", Map.of("Value", Map.of("Fn::GetAtt", List.of("Key", "KeyFingerprint"))));
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", outputs));
    }

    /** A key whose PublicKeyMaterial is {@code material}, with a blank Material parameter. */
    private static String parameterTemplate(String name, Object material, String useImport)
            throws JsonProcessingException {
        return MAPPER.writeValueAsString(Map.of(
                "Parameters", Map.of(
                        "Material", Map.of("Type", "String", "Default", ""),
                        "UseImport", Map.of("Type", "String", "Default", useImport)),
                "Conditions", Map.of("Import", Map.of("Fn::Equals", List.of(Map.of("Ref", "UseImport"), "true"))),
                "Resources", Map.of("Key", Map.of("Type", "AWS::EC2::KeyPair",
                        "Properties", Map.of("KeyName", name, "PublicKeyMaterial", material)))));
    }

    private static void deploy(String action, String stack, String template) {
        cfn(action, stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private static void assertStatus(String stack, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expected, state.status(), state.reason());
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String body = cfn("DescribeStacks", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static String physicalId(String stack) {
        String body = cfn("DescribeStackResources", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "PhysicalResourceId").get("Key");
    }

    private static boolean hasEvent(String stack, String physicalId, String status) {
        return events(stack).stream().anyMatch(event -> "Key".equals(event.get("LogicalResourceId"))
                && physicalId.equals(event.get("PhysicalResourceId")) && status.equals(event.get("ResourceStatus")));
    }

    private static List<Map<String, String>> events(String stack) {
        String body = cfn("DescribeStackEvents", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractGroups(body, "member");
    }

    /** The {@code Replacement} a change set to {@code template} reports for the key. */
    private static String replacement(String stack, String changeSet, String template) {
        cfn("CreateChangeSet", stack).formParam("ChangeSetName", changeSet).formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template).post("/").then().statusCode(200);
        return cfn("DescribeChangeSet", stack).formParam("ChangeSetName", changeSet).post("/")
                .then().statusCode(200).extract().xmlPath()
                .getString("**.find { it.name() == 'ResourceChange' }.Replacement");
    }

    private static RequestSpecification ec2(String action) {
        return given().contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }

    private static XmlPath describeKeyPair(String name) {
        return ec2("DescribeKeyPairs").formParam("KeyName.1", name).post("/")
                .then().statusCode(200).extract().xmlPath();
    }

    private static String keyPairId(String name) {
        return describeKeyPair(name).getString("DescribeKeyPairsResponse.keySet.item.keyPairId");
    }

    private static void assertKeyPairMissing(String name) {
        Response response = ec2("DescribeKeyPairs").formParam("KeyName.1", name).post("/");
        assertEquals(400, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("InvalidKeyPair.NotFound"), response.asString());
    }

    /** The private key EC2 holds for the key pair, read past the API, which never returns it again. */
    private String storedPrivateKey(String keyPairId) {
        return ec2Service.describeKeyPairs(REGION, List.of(), List.of(keyPairId)).getFirst().getKeyMaterial();
    }

    private static Response ssm(String operation, Map<String, ?> body) throws JsonProcessingException {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM." + operation).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static JsonPath parameter(String name) throws JsonProcessingException {
        Response response = ssm("GetParameter", Map.of("Name", name, "WithDecryption", true));
        assertEquals(200, response.statusCode(), "GetParameter " + name);
        return response.jsonPath();
    }

    private static void assertParameterMissing(String name) throws JsonProcessingException {
        Response response = ssm("GetParameter", Map.of("Name", name));
        assertEquals(400, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("ParameterNotFound"), response.asString());
    }

    /** The names of every parameter under {@code /ec2/keypair}, the path the stack stores private keys in. */
    private static Set<String> keyPairParameters() throws JsonProcessingException {
        Set<String> names = new TreeSet<>();
        String token = null;
        do {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("Path", "/ec2/keypair");
            if (token != null) {
                request.put("NextToken", token);
            }
            JsonPath page = ssm("GetParametersByPath", request).then().statusCode(200).extract().jsonPath();
            names.addAll(page.getList("Parameters.Name", String.class));
            token = page.getString("NextToken");
        } while (token != null && !token.isEmpty());
        return names;
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
