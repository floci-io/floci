package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryXmlToJsonTest {

    private static final String NS = "http://cloudformation.amazonaws.com/doc/2010-05-15/";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void scalarResultField() throws Exception {
        String xml = "<CreateStackSetResponse xmlns=\"" + NS + "\">"
                + "<CreateStackSetResult><StackSetId>my-set:abc-123</StackSetId></CreateStackSetResult>"
                + "<ResponseMetadata><RequestId>r1</RequestId></ResponseMetadata></CreateStackSetResponse>";
        JsonNode json = QueryXmlToJson.convert(xml, "CreateStackSetResult", mapper);
        assertEquals("my-set:abc-123", json.path("StackSetId").asText());
    }

    @Test
    void nestedObjectWithMemberLists() throws Exception {
        String xml = "<DescribeStackSetResponse xmlns=\"" + NS + "\"><DescribeStackSetResult><StackSet>"
                + "<StackSetName>s</StackSetName><Status>ACTIVE</Status>"
                + "<Capabilities><member>CAPABILITY_IAM</member><member>CAPABILITY_NAMED_IAM</member></Capabilities>"
                + "<Parameters><member><ParameterKey>K</ParameterKey><ParameterValue>V</ParameterValue></member></Parameters>"
                + "</StackSet></DescribeStackSetResult></DescribeStackSetResponse>";
        JsonNode json = QueryXmlToJson.convert(xml, "DescribeStackSetResult", mapper);
        JsonNode ss = json.path("StackSet");
        assertEquals("ACTIVE", ss.path("Status").asText());
        assertTrue(ss.path("Capabilities").isArray());
        assertEquals(2, ss.path("Capabilities").size());
        assertEquals("CAPABILITY_IAM", ss.path("Capabilities").get(0).asText());
        assertTrue(ss.path("Parameters").isArray());
        assertEquals("K", ss.path("Parameters").get(0).path("ParameterKey").asText());
        assertEquals("V", ss.path("Parameters").get(0).path("ParameterValue").asText());
    }

    @Test
    void summariesMemberListBecomesArray() throws Exception {
        String xml = "<ListStackInstancesResponse xmlns=\"" + NS + "\"><ListStackInstancesResult><Summaries>"
                + "<member><Account>111111111111</Account><Region>us-east-1</Region></member>"
                + "<member><Account>222222222222</Account><Region>us-east-1</Region></member>"
                + "</Summaries></ListStackInstancesResult></ListStackInstancesResponse>";
        JsonNode json = QueryXmlToJson.convert(xml, "ListStackInstancesResult", mapper);
        assertTrue(json.path("Summaries").isArray());
        assertEquals(2, json.path("Summaries").size());
        assertEquals("111111111111", json.path("Summaries").get(0).path("Account").asText());
        assertEquals("us-east-1", json.path("Summaries").get(1).path("Region").asText());
    }

    @Test
    void emptyResultWhenWrapperAbsent() throws Exception {
        String xml = "<DeleteStackSetResponse xmlns=\"" + NS + "\">"
                + "<ResponseMetadata><RequestId>r</RequestId></ResponseMetadata></DeleteStackSetResponse>";
        JsonNode json = QueryXmlToJson.convert(xml, "DeleteStackSetResult", mapper);
        assertTrue(json.isObject());
        assertTrue(json.isEmpty());
    }
}
