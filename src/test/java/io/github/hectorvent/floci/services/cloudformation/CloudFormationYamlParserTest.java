package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFormationYamlParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String yaml) throws Exception {
        return new CloudFormationYamlParser(mapper).parse(yaml);
    }

    @Test
    void cidrShorthandExpandsToFnCidr() throws Exception {
        JsonNode cidr = parse("Value: !Cidr [\"10.0.0.0/16\", 4, 8]").get("Value").get("Fn::Cidr");
        assertTrue(cidr.isArray());
        assertEquals("10.0.0.0/16", cidr.get(0).asText());
        assertEquals(4, cidr.get(1).asInt());
        assertEquals(8, cidr.get(2).asInt());
    }

    @Test
    void getAzsShorthandExpandsToFnGetAZs() throws Exception {
        assertEquals("", parse("Value: !GetAZs \"\"").get("Value").get("Fn::GetAZs").asText());
    }

    @Test
    void cidrShorthandMatchesLongFormInNestedSelect() throws Exception {
        JsonNode shorthand = parse("CidrBlock: !Select [1, !Cidr [!GetAtt Vpc.CidrBlock, 4, 8]]");
        JsonNode longForm = parse("""
            CidrBlock:
              Fn::Select:
                - 1
                - Fn::Cidr:
                    - Fn::GetAtt: [Vpc, CidrBlock]
                    - 4
                    - 8
            """);
        assertEquals(longForm, shorthand);
    }
}
