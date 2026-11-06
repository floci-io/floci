package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bodies measured on CloudFormation (eu-west-1, 2026-10-21) and the words it answers for each. */
class CloudFormationTemplateParserTest {

    private final CloudFormationTemplateParser parser = new CloudFormationTemplateParser(new ObjectMapper());

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"Resources\": {|YAML not well-formed. (line 1, column 16)",
        "Resources:\\n  A: [unclosed\\n|YAML not well-formed. (line 3, column 1)",
        "Resources:\\n\\tA: x\\n|YAML not well-formed. (line 2, column 1)",
        "Resources: {A: {Type: AWS::SNS::Topic}|YAML not well-formed. (line 1, column 39)",
        "Resources:\\n  A:\\n    Type: AWS::SNS::Topic\\n    Properties: !Bogus x\\n|YAML not well-formed. (line 4, column 17)",
        "{\"a\": 1|YAML not well-formed. (line 1, column 8)",
        " \\n{\"a\": 1|YAML not well-formed. (line 2, column 8)",
        "{\"Resources\": {\"A\": {\"Type\": \"AWS::SNS::Topic\"}}} trailing|YAML not well-formed. (line 1, column 51)",
        "[1, 2|YAML not well-formed. (line 1, column 6)",
        "{\"Resources\": {\"A\": {\"Type\": \"AWS::SNS::Topic\"}}|JSON not well-formed. (line 1, column 49)",
        "{\"a\": 1}}|JSON not well-formed. (line 1, column 9)",
        "{\"a\": [1}|JSON not well-formed. (line 1, column 9)",
        "[{\"a\": 1}|YAML not well-formed. (line 1, column 10)",
    })
    void aBodyThatIsNeitherJsonNorYamlIsRefusedWhereCloudFormationStops(String body, String message) {
        CloudFormationTemplateParser.TemplateFormatException refused = assertThrows(
                CloudFormationTemplateParser.TemplateFormatException.class,
                () -> parser.parse(body.replace("\\n", "\n").replace("\\t", "\t")));

        assertEquals(message, refused.getMessage());
    }

    @Test
    void whatIsNotStrictJsonStillReadsAsYaml() throws Exception {
        assertEquals(1, parser.parse("{a: 1}").path("a").asInt());
        assertEquals(1, parser.parse("{\"a\": 1, }").path("a").asInt());
        JsonNode trailingComma = parser.parse("{\n  \"Resources\": {\n    \"A\": {\"Type\": \"AWS::SNS::Topic\",}\n  }\n}");
        assertEquals("AWS::SNS::Topic", trailingComma.path("Resources").path("A").path("Type").asText());
    }

    @Test
    void aScalarOrAListReadsAsWhatItIs() throws Exception {
        assertTrue(parser.parse("just a string").isTextual());
        assertTrue(parser.parse("42").isNumber());
        assertTrue(parser.parse("[1, 2]").isArray());
    }

    @Test
    void duplicateKeysAreTolerated() throws Exception {
        String json = "{\"Resources\": {\"A\": {\"Type\": \"AWS::SNS::Topic\"}, \"A\": {\"Type\": \"AWS::SNS::Topic\"}}}";
        assertEquals("AWS::SNS::Topic", parser.parse(json).path("Resources").path("A").path("Type").asText());
        String yaml = "Resources:\n  A:\n    Type: AWS::SNS::Topic\n  A:\n    Type: AWS::SNS::Topic\n";
        assertEquals("AWS::SNS::Topic", parser.parse(yaml).path("Resources").path("A").path("Type").asText());
    }
}
