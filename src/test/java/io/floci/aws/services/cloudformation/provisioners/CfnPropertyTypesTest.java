package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestShapeModel;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property types vendored from the resource schemas, and the readers' walk over the shapes the
 * generator writes: structures, lists, maps, scalars and values the schema leaves free.
 */
class CfnPropertyTypesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RequestShapeModel MODEL = model("""
            {
              "operations": {"AWS::Test::Thing": "AWS::Test::Thing"},
              "shapes": {
                "AWS::Test::Thing": {"type": "structure", "members": {
                  "Config": "AWS::Test::Thing/Config", "Pattern": "Json", "Ratio": "Double"}},
                "AWS::Test::Thing/Config": {"type": "structure", "members": {
                  "Headers": "AWS::Test::Thing/Config.Headers", "Count": "Integer",
                  "Children": "AWS::Test::Thing/Config.Children"}},
                "AWS::Test::Thing/Config.Headers": {"type": "map", "value": "String"},
                "AWS::Test::Thing/Config.Children": {"type": "list", "member": "AWS::Test::Thing/Config"},
                "String": {"type": "string"},
                "Integer": {"type": "integer"},
                "Boolean": {"type": "boolean"},
                "Double": {"type": "double"},
                "Json": {"type": "json"}
              }
            }
            """);

    @Test
    void eachPropertyTheProvisionersReadHasItsSchemaShape() {
        assertEquals("AWS::Events::Rule.Targets", CfnPropertyTypes.propertyShape("AWS::Events::Rule", "Targets"));
        assertEquals("AWS::Lambda::Function/Environment",
                CfnPropertyTypes.propertyShape("AWS::Lambda::Function", "Environment"));
        assertEquals("AWS::Lambda::LayerVersion.CompatibleRuntimes",
                CfnPropertyTypes.propertyShape("AWS::Lambda::LayerVersion", "CompatibleRuntimes"));
        assertEquals("AWS::AutoScaling::LaunchConfiguration/MetadataOptions",
                CfnPropertyTypes.propertyShape("AWS::AutoScaling::LaunchConfiguration", "MetadataOptions"));
        assertEquals("AWS::ECS::TaskDefinition.ContainerDefinitions",
                CfnPropertyTypes.propertyShape("AWS::ECS::TaskDefinition", "ContainerDefinitions"));
    }

    @Test
    void aPropertyTheVendoredFileLacksIsAnErrorOfTheBuild() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CfnPropertyTypes.propertyShape("AWS::Events::Rule", "NoSuchProperty"));
        assertTrue(e.getMessage().contains("regen_cfn_property_types.py"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> CfnPropertyTypes.propertyShape("AWS::No::Type", "Name"));
    }

    @Test
    void aMapReadsEachValueAsItsValueShape() {
        JsonNode read = read("{\"Headers\": {\"a\": \"x\", \"b\": 5}, \"Count\": \"3\"}", "AWS::Test::Thing/Config");

        assertEquals(json("{\"Headers\": {\"a\": \"x\", \"b\": \"5\"}, \"Count\": 3}"), read);
        AwsException e = assertThrows(AwsException.class,
                () -> read("{\"Headers\": \"x\"}", "AWS::Test::Thing/Config"));
        assertEquals("Property [Headers] expected type: JSONObject, found: String", e.getMessage());
    }

    @Test
    void aStructureNestedInAListIsReadAtEachLevel() {
        AwsException e = assertThrows(AwsException.class,
                () -> read("{\"Children\": [{\"Count\": 4.5}]}", "AWS::Test::Thing/Config"));
        assertEquals("Property [Count] expected type: Integer, found: Float", e.getMessage());
    }

    @Test
    void aValueTheSchemaLeavesFreeIsResolvedAsItIs() {
        JsonNode read = read("{\"Pattern\": {\"source\": [{\"Fn::Sub\": \"app\"}]}, \"Ratio\": \"half\"}",
                "AWS::Test::Thing");

        assertEquals(json("{\"Pattern\": {\"source\": [\"app\"]}, \"Ratio\": \"half\"}"), read);
    }

    @Test
    void aValueLeftOutByNoValueIsAbsent() {
        assertNull(new CfnPropertyTypes.Reader(engine(), MODEL)
                .read("Config", json("{\"Ref\": \"AWS::NoValue\"}"), "AWS::Test::Thing/Config"));
    }

    private static final String HINT = ". This may occur when a numeric value exceeds 32-bit integer max"
            + " (2147483647). Consider using 'number' type or ensure value fits in integer range.";
    private static final String CONTAINER = "#/ContainerDefinitions/0";
    private static final String MEMORY = CONTAINER + "/Memory: expected type: Integer, found: String" + HINT;
    private static final String LINUX = CONTAINER + "/LinuxParameters";

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-19): an AWS::ECS::TaskDefinition gets no
     * template validation, and the ECS handler fails it with every violation of the unconverted
     * model when a value does not convert, Memory 128 included, in the schema's order whatever the
     * template's; when every value converts, with the required members the model lacks, grouped
     * where their paths meet.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ecsTaskDefinitions")
    void anEcsTaskDefinitionFailsWhereTheEcsHandlerDoes(String measured, String properties, String violations) {
        assertHandlerReaderFailure("AWS::ECS::TaskDefinition", properties, "Model validation failed (" + violations);
    }

    static Stream<Arguments> ecsTaskDefinitions() {
        return Stream.of(
                Arguments.of("Add a string", container("\"Memory\": 128, \"LinuxParameters\": {\"Capabilities\": {\"Add\": \"SYS_PTRACE\"}}"),
                        MEMORY + "\n" + LINUX + "/Capabilities/Add: expected type: JSONArray, found: String)"),
                Arguments.of("Add an object item", container("\"Memory\": 128, \"LinuxParameters\": {\"Capabilities\": {\"Add\": [{}]}}"),
                        MEMORY + "\n" + LINUX + "/Capabilities/Add/0: expected type: String, found: JSONObject)"),
                Arguments.of("Capabilities a string", container("\"Memory\": 128, \"LinuxParameters\": {\"Capabilities\": \"x\"}"),
                        MEMORY + "\n" + LINUX + "/Capabilities: expected type: JSONObject, found: String)"),
                Arguments.of("LinuxParameters a string", container("\"Memory\": 128, \"LinuxParameters\": \"x\""),
                        MEMORY + "\n" + LINUX + ": expected type: JSONObject, found: String)"),
                Arguments.of("LinuxParameters a list", container("\"Memory\": 128, \"LinuxParameters\": [\"x\"]"),
                        MEMORY + "\n" + LINUX + ": expected type: JSONObject, found: JSONArray)"),
                Arguments.of("InitProcessEnabled yes", container("\"Memory\": 128, \"LinuxParameters\": {\"InitProcessEnabled\": \"yes\"}"),
                        MEMORY + "\n" + LINUX + "/InitProcessEnabled: expected type: Boolean, found: String)"),
                Arguments.of("InitProcessEnabled an object", container("\"Memory\": 128, \"LinuxParameters\": {\"InitProcessEnabled\": {}}"),
                        MEMORY + "\n" + LINUX + "/InitProcessEnabled: expected type: Boolean, found: JSONObject)"),
                Arguments.of("SharedMemorySize abc", container("\"Memory\": 128, \"LinuxParameters\": {\"SharedMemorySize\": \"abc\"}"),
                        MEMORY + "\n" + LINUX + "/SharedMemorySize: expected type: Integer, found: String" + HINT + ")"),
                Arguments.of("SharedMemorySize 4.5", container("\"Memory\": 128, \"LinuxParameters\": {\"SharedMemorySize\": 4.5}"),
                        MEMORY + "\n" + LINUX + "/SharedMemorySize: expected type: Integer, found: String" + HINT + ")"),
                Arguments.of("SharedMemorySize true", container("\"Memory\": 128, \"LinuxParameters\": {\"SharedMemorySize\": true}"),
                        MEMORY + "\n" + LINUX + "/SharedMemorySize: expected type: Integer, found: String" + HINT + ")"),
                Arguments.of("MaxSwap beyond 32 bits", container("\"Memory\": 128, \"LinuxParameters\": {\"MaxSwap\": 3000000000}"),
                        MEMORY + "\n" + LINUX + "/MaxSwap: expected type: Integer, found: String" + HINT + ")"),
                Arguments.of("Devices a string", container("\"Memory\": 128, \"LinuxParameters\": {\"Devices\": \"x\"}"),
                        MEMORY + "\n" + LINUX + "/Devices: expected type: JSONArray, found: String)"),
                Arguments.of("HostPath an object", container("\"Memory\": 128, \"LinuxParameters\": {\"Devices\": [{\"HostPath\": {}}]}"),
                        MEMORY + "\n" + LINUX + "/Devices/0/HostPath: expected type: String, found: JSONObject)"),
                Arguments.of("MountOptions a string", container("\"Memory\": 128, \"LinuxParameters\": {\"Tmpfs\": [{\"ContainerPath\": \"/t\", \"Size\": 64, \"MountOptions\": \"rw\"}]}"),
                        MEMORY + "\n" + LINUX + "/Tmpfs/0/Size: expected type: Integer, found: String" + HINT + "\n" + LINUX
                                + "/Tmpfs/0/MountOptions: expected type: JSONArray, found: String)"),
                Arguments.of("MountOptions before Size in the template", container("\"Memory\": 128, \"LinuxParameters\": {\"Tmpfs\": [{\"MountOptions\": \"rw\", \"ContainerPath\": \"/t\", \"Size\": 64}]}"),
                        MEMORY + "\n" + LINUX + "/Tmpfs/0/Size: expected type: Integer, found: String" + HINT + "\n" + LINUX
                                + "/Tmpfs/0/MountOptions: expected type: JSONArray, found: String)"),
                Arguments.of("LinuxParameters before Memory in the template", "{\"ContainerDefinitions\": [{\"LinuxParameters\": {\"Capabilities\": {\"Add\": \"X\"}}, \"Memory\": 128, \"Name\": \"app\", \"Image\": \"busybox\"}]}",
                        MEMORY + "\n" + LINUX + "/Capabilities/Add: expected type: JSONArray, found: String)"),
                Arguments.of("Memory abc", container("\"Memory\": \"abc\", \"LinuxParameters\": {}"), MEMORY + ")"),
                Arguments.of("Memory an object", container("\"Memory\": {}"),
                        CONTAINER + "/Memory: expected type: Integer, found: JSONObject)"),
                Arguments.of("Essential tRuE", container("\"Memory\": 128, \"Essential\": \"tRuE\""),
                        MEMORY + "\n" + CONTAINER + "/Essential: expected type: Boolean, found: String)"),
                Arguments.of("Essential 1", container("\"Memory\": 128, \"Essential\": \"1\""),
                        MEMORY + "\n" + CONTAINER + "/Essential: expected type: Boolean, found: String)"),
                Arguments.of("Name an object", "{\"ContainerDefinitions\": [{\"Name\": {\"a\": 1}, \"Image\": \"busybox\", \"Memory\": 128}]}",
                        MEMORY + "\n" + CONTAINER + "/Name: expected type: String, found: JSONObject)"),
                Arguments.of("ContainerDefinitions a string", "{\"ContainerDefinitions\": \"x\"}",
                        "#/ContainerDefinitions: expected type: JSONArray, found: String)"),
                Arguments.of("a number for text", container("\"Memory\": 128, \"Environment\": [{\"Name\": \"A\", \"Value\": 5}], \"LinuxParameters\": {\"Devices\": \"x\"}"),
                        MEMORY + "\n" + LINUX + "/Devices: expected type: JSONArray, found: String)"),
                Arguments.of("every integer and boolean, in the schema's order", container("\"Memory\": 128, \"PortMappings\": [{\"ContainerPort\": 80}], \"Essential\": true, \"LinuxParameters\": {\"Devices\": \"x\"}"),
                        MEMORY + "\n" + CONTAINER + "/Essential: expected type: Boolean, found: String\n" + LINUX
                                + "/Devices: expected type: JSONArray, found: String\n" + CONTAINER
                                + "/PortMappings/0/ContainerPort: expected type: Integer, found: String" + HINT + ")"),
                Arguments.of("two containers", "{\"ContainerDefinitions\": [{\"Name\": \"a\", \"Image\": \"busybox\", \"Memory\": 128, \"Essential\": true},"
                                + " {\"Name\": \"b\", \"Image\": \"busybox\", \"Memory\": 64, \"Essential\": false, \"LinuxParameters\": {\"Capabilities\": {\"Add\": \"X\"}}}]}",
                        MEMORY + "\n" + CONTAINER + "/Essential: expected type: Boolean, found: String\n#/ContainerDefinitions/1/Memory:"
                                + " expected type: Integer, found: String" + HINT + "\n#/ContainerDefinitions/1/Essential: expected type:"
                                + " Boolean, found: String\n#/ContainerDefinitions/1/LinuxParameters/Capabilities/Add: expected type:"
                                + " JSONArray, found: String)"),
                Arguments.of("the task level", "{\"Memory\": \"512\", \"EphemeralStorage\": {\"SizeInGiB\": 30}, \"ContainerDefinitions\": [{\"Name\": \"app\","
                                + " \"Image\": \"busybox\", \"Cpu\": 10, \"LinuxParameters\": {\"Capabilities\": {\"Add\": \"X\"}, \"InitProcessEnabled\": true}}]}",
                        CONTAINER + "/Cpu: expected type: Integer, found: String" + HINT + "\n" + LINUX
                                + "/Capabilities/Add: expected type: JSONArray, found: String\n" + LINUX
                                + "/InitProcessEnabled: expected type: Boolean, found: String\n#/EphemeralStorage/SizeInGiB: expected"
                                + " type: Integer, found: String" + HINT + ")"),
                Arguments.of("a missing required member beside a type", container("\"Memory\": 128, \"LinuxParameters\": {\"Capabilities\": {\"Add\": \"X\"}, \"Tmpfs\": [{\"ContainerPath\": \"/t\"}]}"),
                        MEMORY + "\n" + LINUX + "/Capabilities/Add: expected type: JSONArray, found: String\n" + LINUX
                                + "/Tmpfs/0: required key [Size] not found)"),
                Arguments.of("required members before the object's members", container("\"Memory\": 128, \"LinuxParameters\": {\"Tmpfs\": [{\"ContainerPath\": \"/t\", \"MountOptions\": \"rw\"}]}"),
                        MEMORY + "\n" + LINUX + "/Tmpfs/0: required key [Size] not found\n" + LINUX
                                + "/Tmpfs/0/MountOptions: expected type: JSONArray, found: String)"),
                Arguments.of("no Name and no Image beside a type", "{\"ContainerDefinitions\": [{\"Memory\": 128, \"LinuxParameters\": {\"Capabilities\": {\"Add\": \"X\"}}}]}",
                        CONTAINER + ": required key [Name] not found\n" + CONTAINER + ": required key [Image] not found\n" + MEMORY
                                + "\n" + LINUX + "/Capabilities/Add: expected type: JSONArray, found: String)"),
                Arguments.of("Tmpfs without Size", container("\"Memory\": 128, \"LinuxParameters\": {\"Tmpfs\": [{\"ContainerPath\": \"/t\"}]}"),
                        LINUX + "/Tmpfs/0: required key [Size] not found)"),
                Arguments.of("no Name", "{\"ContainerDefinitions\": [{\"Image\": \"busybox\", \"Memory\": 128}]}",
                        CONTAINER + ": required key [Name] not found)"),
                Arguments.of("no Name and no Image", "{\"ContainerDefinitions\": [{\"Memory\": 128, \"Essential\": true}]}",
                        CONTAINER + ": 2 schema violations found)\n" + CONTAINER + ": required key [Name] not found (" + CONTAINER
                                + ")\n" + CONTAINER + ": required key [Image] not found (" + CONTAINER + ")"),
                Arguments.of("no Name and Tmpfs without Size", "{\"ContainerDefinitions\": [{\"Image\": \"busybox\", \"Memory\": 128,"
                                + " \"LinuxParameters\": {\"Tmpfs\": [{\"ContainerPath\": \"/t\"}]}}]}",
                        CONTAINER + ": 2 schema violations found)\n" + CONTAINER + ": required key [Name] not found (" + CONTAINER
                                + ")\n" + LINUX + "/Tmpfs/0: required key [Size] not found (" + LINUX + "/Tmpfs/0)"),
                Arguments.of("an empty ulimit", container("\"Memory\": 128, \"Ulimits\": [{}]"),
                        CONTAINER + "/Ulimits/0: 3 schema violations found)\n" + CONTAINER + "/Ulimits/0: required key [HardLimit] not"
                                + " found (" + CONTAINER + "/Ulimits/0)\n" + CONTAINER + "/Ulimits/0: required key [Name] not found ("
                                + CONTAINER + "/Ulimits/0)\n" + CONTAINER + "/Ulimits/0: required key [SoftLimit] not found ("
                                + CONTAINER + "/Ulimits/0)"),
                Arguments.of("no Name and an empty ulimit", "{\"ContainerDefinitions\": [{\"Image\": \"busybox\", \"Memory\": 128, \"Ulimits\": [{}]}]}",
                        CONTAINER + ": 4 schema violations found)\n" + CONTAINER + ": required key [Name] not found (" + CONTAINER
                                + ")\n" + CONTAINER + "/Ulimits/0: 3 schema violations found (" + CONTAINER + "/Ulimits/0)"),
                Arguments.of("two containers without Name", "{\"ContainerDefinitions\": [{\"Image\": \"busybox\", \"Memory\": 128},"
                                + " {\"Image\": \"busybox\", \"Memory\": 64}]}",
                        "#/ContainerDefinitions: 2 schema violations found)\n" + CONTAINER + ": required key [Name] not found ("
                                + CONTAINER + ")\n#/ContainerDefinitions/1: required key [Name] not found (#/ContainerDefinitions/1)"),
                Arguments.of("the task level and a container", "{\"ContainerDefinitions\": [{\"Image\": \"busybox\", \"Memory\": 128}],"
                                + " \"ProxyConfiguration\": {\"Type\": \"APPMESH\"}}",
                        "#: 2 schema violations found)\n#/ProxyConfiguration: required key [ContainerName] not found"
                                + " (#/ProxyConfiguration)\n" + CONTAINER + ": required key [Name] not found (" + CONTAINER + ")"));
    }

    /** Measured: the values the ECS handler converts, and a number where text is expected, read as text. */
    @Test
    void anEcsTaskDefinitionIsConvertedAsTheEcsHandlerConvertsIt() {
        JsonNode read = new CfnPropertyTypes.HandlerReader(engine()).read("AWS::ECS::TaskDefinition", json("""
                {"Family": "web", "Memory": 512, "ContainerDefinitions": [
                  {"Name": "a", "Image": "busybox", "Memory": "+128", "Essential": "True",
                   "Environment": [{"Name": "A", "Value": 5}],
                   "LinuxParameters": {"InitProcessEnabled": "TRUE", "SharedMemorySize": "64",
                                       "Devices": [{"HostPath": 5, "ContainerPath": "/dev/x"}]}},
                  {"Name": "b", "Image": "busybox", "Memory": " 128", "Essential": " true"},
                  {"Name": "c", "Image": "busybox", "Memory": "0128", "Cpu": {"Ref": "AWS::NoValue"}}]}
                """));

        assertEquals(json("""
                {"Family": "web", "Memory": "512", "ContainerDefinitions": [
                  {"Name": "a", "Image": "busybox", "Memory": 128, "Essential": true,
                   "Environment": [{"Name": "A", "Value": "5"}],
                   "LinuxParameters": {"InitProcessEnabled": true, "SharedMemorySize": 64,
                                       "Devices": [{"HostPath": "5", "ContainerPath": "/dev/x"}]}},
                  {"Name": "b", "Image": "busybox", "Memory": 128, "Essential": true},
                  {"Name": "c", "Image": "busybox", "Memory": 128}]}
                """), read);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSesRequiredValues")
    void handlerReaderRejectsNullAndBlankRequiredMembers(String caseName, String properties, String violation) {
        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", properties,
                "Model validation failed (" + violation + ")");
    }

    static Stream<Arguments> invalidSesRequiredValues() {
        return Stream.of(
                Arguments.of("null root name",
                        "{\"ConfigurationSetName\":null,\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"]}}",
                        "#: required key [ConfigurationSetName] not found"),
                Arguments.of("blank root name",
                        "{\"ConfigurationSetName\":\"  \",\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"]}}",
                        "#: required key [ConfigurationSetName] not found"),
                Arguments.of("resolved blank root name",
                        "{\"ConfigurationSetName\":{\"Fn::Sub\":\"\"},\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"]}}",
                        "#: required key [ConfigurationSetName] not found"),
                Arguments.of("omitted root name",
                        "{\"ConfigurationSetName\":{\"Ref\":\"AWS::NoValue\"},\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"]}}",
                        "#: required key [ConfigurationSetName] not found"),
                Arguments.of("null event list",
                        "{\"ConfigurationSetName\":\"set\",\"EventDestination\":{\"MatchingEventTypes\":null}}",
                        "#/EventDestination: required key [MatchingEventTypes] not found"),
                Arguments.of("null nested ARN",
                        "{\"ConfigurationSetName\":\"set\",\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"],\"SnsDestination\":{\"TopicARN\":null}}}",
                        "#/EventDestination/SnsDestination: required key [TopicARN] not found"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSesArnValues")
    void handlerReaderRejectsArnValuesOutsideSchemaConstraints(String caseName, String destination,
            String member, String arn, String violation) {
        String properties = """
                {"ConfigurationSetName":"set","EventDestination":{
                  "MatchingEventTypes":["SEND"],"%s":{"%s":"%s"}}}
                """.formatted(destination, member, arn);

        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", properties,
                "Model validation failed (#/EventDestination/" + destination + "/" + member + ": "
                        + violation + ")");
    }

    static Stream<Arguments> invalidSesArnValues() {
        String snsPrefix = "arn:aws:sns:us-east-1:000000000000:";
        return Stream.of(
                Arguments.of("SNS ARN has wrong service", "SnsDestination", "TopicARN",
                        "arn:aws:sqs:us-east-1:000000000000:topic-name",
                        "failed validation constraint for keyword [pattern]"),
                Arguments.of("SNS ARN is below schema minimum", "SnsDestination", "TopicARN",
                        "arn:aws:sns:a:000000000000:x", "expected minLength: 36, actual: 28"),
                Arguments.of("SNS ARN exceeds schema maximum", "SnsDestination", "TopicARN",
                        snsPrefix + "x".repeat(1100 - snsPrefix.length()),
                        "expected maxLength: 1024, actual: 1100"),
                Arguments.of("EventBridge ARN has wrong service", "EventBridgeDestination", "EventBusArn",
                        "arn:aws:sns:us-east-1:000000000000:event-bus/topic-name",
                        "failed validation constraint for keyword [pattern]"));
    }

    @Test
    void handlerReaderGroupsEveryFailingConstraintForBlankTopicArn() {
        String path = "#/EventDestination/SnsDestination/TopicARN";
        String properties = """
                {"ConfigurationSetName":"set","EventDestination":{"MatchingEventTypes":["SEND"],
                "SnsDestination":{"TopicARN":""}}}
                """;

        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", properties,
                "Model validation failed (" + path + ": 2 schema violations found)\n"
                        + path + ": expected minLength: 36, actual: 0 (" + path + ")\n"
                        + path + ": failed validation constraint for keyword [pattern] (" + path + ")");
    }

    @Test
    void handlerReaderGroupsRequiredAndStringConstraintViolations() {
        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", """
                {"ConfigurationSetName":"set","EventDestination":{"SnsDestination":{"TopicARN":""}}}
                """, """
                Model validation failed (#/EventDestination: 3 schema violations found)
                #/EventDestination: required key [MatchingEventTypes] not found (#/EventDestination)
                #/EventDestination/SnsDestination/TopicARN: 2 schema violations found (#/EventDestination/SnsDestination/TopicARN)
                """.stripTrailing());
    }

    @Test
    void handlerReaderChecksEveryStringConstraintAlongsideAnUnconvertedType() {
        RequestShapeModel model = model("""
                {"operations":{"AWS::Test::Thing":"Root"},"shapes":{
                  "Root":{"type":"structure","members":{"Name":"Name","Count":"Count"}},
                  "Name":{"type":"string","minLength":2,"pattern":"[A-Z]+"},
                  "Count":{"type":"integer"}}}
                """);

        AwsException failure = assertThrows(AwsException.class,
                () -> new CfnPropertyTypes.HandlerReader(engine(), model)
                        .read("AWS::Test::Thing", json("{\"Name\":\"\",\"Count\":\"not-a-number\"}")));

        assertEquals("Model validation failed (#/Name: expected minLength: 2, actual: 0\n"
                + "#/Name: failed validation constraint for keyword [pattern]\n"
                + "#/Count: expected type: Integer, found: String" + HINT + ")", failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSesDestinationStrings")
    void handlerReaderChecksEveryConstrainedSesString(String caseName, String properties,
            String expectedMessage) {
        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", properties,
                "Model validation failed (" + expectedMessage + ")");
    }

    static Stream<Arguments> invalidSesDestinationStrings() {
        String dimensions = """
                {"ConfigurationSetName":"set","EventDestination":{"MatchingEventTypes":["SEND"],
                "CloudWatchDestination":{"DimensionConfigurations":[{"DimensionValueSource":"emailHeader",
                "DimensionName":"%s","DefaultDimensionValue":"%s"}]}}}
                """;
        return Stream.of(
                Arguments.of("event destination name pattern", """
                        {"ConfigurationSetName":"set","EventDestination":{
                        "MatchingEventTypes":["SEND"],"Name":"bad name"}}
                        """, "#/EventDestination/Name: failed validation constraint for keyword [pattern]"),
                Arguments.of("dimension name minimum", dimensions.formatted("", "value"),
                        groupedStringViolations(
                                "#/EventDestination/CloudWatchDestination/DimensionConfigurations/0/DimensionName",
                                "expected minLength: 1, actual: 0")),
                Arguments.of("default dimension value maximum", dimensions.formatted("name", "x".repeat(257)),
                        groupedStringViolations(
                                "#/EventDestination/CloudWatchDestination/DimensionConfigurations/0/DefaultDimensionValue",
                                "expected maxLength: 256, actual: 257")));
    }

    private static String groupedStringViolations(String path, String lengthViolation) {
        return path + ": 2 schema violations found)\n" + path + ": " + lengthViolation + " (" + path
                + ")\n" + path + ": failed validation constraint for keyword [pattern] (" + path;
    }

    @Test
    void handlerReaderAcceptsValidSesArnsAndUnconstrainedRequiredStrings() {
        JsonNode properties = json("""
                {"ConfigurationSetName":"x","EventDestination":{
                  "Name":"","MatchingEventTypes":["SEND"],
                  "SnsDestination":{"TopicARN":"arn:aws:sns:us-east-1:000000000000:topic-name"},
                  "EventBridgeDestination":{"EventBusArn":"arn:aws:events:us-east-1:000000000000:event-bus/topic-name"},
                  "KinesisFirehoseDestination":{"IAMRoleARN":"role","DeliveryStreamARN":"stream"}}}
                """);

        assertEquals(properties, new CfnPropertyTypes.HandlerReader(engine())
                .read("AWS::SES::ConfigurationSetEventDestination", properties));
    }

    @Test
    void handlerReaderAcceptsSesArnAtSchemaMaximumLength() {
        String prefix = "arn:aws:sns:us-east-1:000000000000:";
        String arn = prefix + "x".repeat(1024 - prefix.length());
        JsonNode properties = json("""
                {"ConfigurationSetName":"set","EventDestination":{
                  "MatchingEventTypes":["SEND"],"SnsDestination":{"TopicARN":"%s"}}}
                """.formatted(arn));

        assertEquals(properties, new CfnPropertyTypes.HandlerReader(engine())
                .read("AWS::SES::ConfigurationSetEventDestination", properties));
    }

    /**
     * A schema pattern is searched for, as the validator CloudFormation runs does, not matched whole: the Studio
     * schema's Name pattern {@code [a-zA-Z0-9_-]+} takes a name with spaces, and refuses one with none of its
     * characters.
     */
    @Test
    void handlerReaderLooksForAPatternInTheValue() {
        String studio = """
                {"Name": "%s", "AuthMode": "IAM", "VpcId": "vpc-0123456789abcdef0", "SubnetIds": ["subnet-1"],
                 "ServiceRole": "arn:aws:iam::000000000000:role/EMRStudio", "WorkspaceSecurityGroupId": "sg-1",
                 "EngineSecurityGroupId": "sg-2", "DefaultS3Location": "s3://bucket/studio"}
                """;
        JsonNode spaced = json(studio.formatted("notebooks for etl"));

        assertEquals(spaced, new CfnPropertyTypes.HandlerReader(engine()).read("AWS::EMR::Studio", spaced));
        AwsException failure = assertThrows(AwsException.class, () -> new CfnPropertyTypes.HandlerReader(engine())
                .read("AWS::EMR::Studio", json(studio.formatted("!!!"))));
        assertEquals("Model validation failed (#/Name: failed validation constraint for keyword [pattern])",
                failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownSesMembers")
    void handlerReaderRejectsExtraneousMembers(String caseName, String properties, String violation) {
        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", properties,
                "Model validation failed (" + violation + ")");
    }

    static Stream<Arguments> unknownSesMembers() {
        return Stream.of(
                Arguments.of("top-level member",
                        "{\"ConfigurationSetName\":\"set\",\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"]},\"Extra\":true}",
                        "#: extraneous key [Extra] is not permitted"),
                Arguments.of("nested member",
                        "{\"ConfigurationSetName\":\"set\",\"EventDestination\":{\"MatchingEventTypes\":[\"SEND\"],\"SnsDestination\":{\"TopicARN\":\"arn:aws:sns:us-east-1:000000000000:topic-name\",\"Extra\":true}}}",
                        "#/EventDestination/SnsDestination: extraneous key [Extra] is not permitted"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLaunchConfigurationProperties")
    void handlerReaderValidatesAnotherVendoredType(String caseName, String properties, String violation) {
        assertHandlerReaderFailure("AWS::AutoScaling::LaunchConfiguration", properties,
                "Model validation failed (" + violation + ")");
    }

    private static void assertHandlerReaderFailure(String resourceType, String properties, String expectedMessage) {
        AwsException failure = assertThrows(AwsException.class,
                () -> new CfnPropertyTypes.HandlerReader(engine())
                        .read(resourceType, json(properties)));

        assertEquals("InvalidRequest", failure.getErrorCode());
        assertEquals(expectedMessage, failure.getMessage());
    }

    static Stream<Arguments> invalidLaunchConfigurationProperties() {
        return Stream.of(
                Arguments.of("null required ImageId",
                        "{\"ImageId\":null,\"InstanceType\":\"t3.micro\"}",
                        "#: required key [ImageId] not found"),
                Arguments.of("blank required InstanceType",
                        "{\"ImageId\":\"ami-123\",\"InstanceType\":\"  \"}",
                        "#: required key [InstanceType] not found"),
                Arguments.of("extraneous root member",
                        "{\"ImageId\":\"ami-123\",\"InstanceType\":\"t3.micro\",\"Extra\":true}",
                        "#: extraneous key [Extra] is not permitted"));
    }

    @ParameterizedTest
    @MethodSource("strictVendoredTypes")
    void handlerReaderRejectsUnmodeledMembersAcrossVendoredTypes(String resourceType) {
        AwsException failure = assertThrows(AwsException.class,
                () -> new CfnPropertyTypes.HandlerReader(engine()).read(resourceType, json("{\"Extra\":true}")));

        assertEquals("InvalidRequest", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("#: extraneous key [Extra] is not permitted"), failure.getMessage());
    }

    static Stream<Arguments> strictVendoredTypes() {
        return RequestShapeModel.load(CfnPropertyTypes.RESOURCE_NAME, "CloudFormation property types")
                .operations().keySet().stream()
                .filter(type -> !"AWS::ECS::TaskDefinition".equals(type))
                .map(Arguments::of);
    }

    @Test
    void handlerReaderLeavesMapEntriesAndFreeFormValuesAlone() {
        JsonNode read = new CfnPropertyTypes.HandlerReader(engine(), MODEL).read("AWS::Test::Thing",
                json("{\"Config\":{\"Headers\":{\"custom\":\"\"}},\"Pattern\":{\"custom\":{\"nested\":1}}}"));

        assertEquals(json("{\"Config\":{\"Headers\":{\"custom\":\"\"}},\"Pattern\":{\"custom\":{\"nested\":1}}}"), read);
    }

    @Test
    void handlerReaderPassesUnmodeledEcsMembersThrough() {
        JsonNode properties = json("{\"ContainerDefinitions\":[{\"Name\":\"app\",\"Image\":\"busybox\",\"Extra\":true}]}");

        assertEquals(properties, new CfnPropertyTypes.HandlerReader(engine())
                .read("AWS::ECS::TaskDefinition", properties));
    }

    @Test
    void handlerReaderReportsKnownMembersBeforeExtraneousMembers() {
        assertHandlerReaderFailure("AWS::SES::ConfigurationSetEventDestination", """
                {"ConfigurationSetName":"set","EventDestination":{
                  "Extra":true,"MatchingEventTypes":["SEND"],"Enabled":"bogus"}}
                """, "Model validation failed (#/EventDestination/Enabled: expected type: Boolean, found: String\n"
                        + "#/EventDestination: extraneous key [Extra] is not permitted)");
    }

    @Test
    void handlerReaderLeavesEcsRequiredBlankStringsToItsExistingPath() {
        JsonNode properties = json("{\"ContainerDefinitions\":[{\"Name\":\"\",\"Image\":\" \"}]}");

        assertEquals(properties, new CfnPropertyTypes.HandlerReader(engine())
                .read("AWS::ECS::TaskDefinition", properties));
    }

    @Test
    void aResourceTypeTheVendoredFileLacksIsAnErrorOfTheBuild() {
        assertThrows(IllegalStateException.class,
                () -> new CfnPropertyTypes.HandlerReader(engine()).read("AWS::No::Type", json("{}")));
    }

    private static String container(String members) {
        return "{\"ContainerDefinitions\": [{\"Name\": \"app\", \"Image\": \"busybox\", " + members + "}]}";
    }

    private static JsonNode read(String value, String shape) {
        CfnPropertyTypes.Reader reader = new CfnPropertyTypes.Reader(engine(), MODEL);
        JsonNode read = reader.read("Config", json(value), shape);
        reader.finish();
        return read;
    }

    private static CloudFormationTemplateEngine engine() {
        return CloudFormationTemplateEngine.standalone("000000000000", "us-east-1", "my-stack", MAPPER, null);
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static RequestShapeModel model(String text) {
        return RequestShapeModel.parse(json(text));
    }
}
