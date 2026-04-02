package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XmlParserTest {

    // --- extractGroupsMulti: nested element resilience ---

    @Test
    void extractGroupsMultiSkipsNestedElementsAndParsesLeaves() {
        String xml = """
                <Root>
                  <Group>
                    <Id>g1</Id>
                    <Arn>arn:example</Arn>
                    <Event>event:one</Event>
                    <Nested>
                      <Deep>value</Deep>
                    </Nested>
                  </Group>
                </Root>
                """;

        List<Map<String, List<String>>> groups = XmlParser.extractGroupsMulti(xml, "Group");

        assertEquals(1, groups.size());
        assertEquals(List.of("g1"), groups.get(0).get("Id"));
        assertEquals(List.of("arn:example"), groups.get(0).get("Arn"));
        assertEquals(List.of("event:one"), groups.get(0).get("Event"));
        assertNull(groups.get(0).get("Nested"));
    }

    @Test
    void extractGroupsMultiNestedElementBeforeLeaves() {
        String xml = """
                <Root>
                  <Group>
                    <Nested><Child>x</Child></Nested>
                    <Name>after-nested</Name>
                  </Group>
                </Root>
                """;

        List<Map<String, List<String>>> groups = XmlParser.extractGroupsMulti(xml, "Group");

        assertEquals(1, groups.size());
        assertEquals(List.of("after-nested"), groups.get(0).get("Name"));
    }

    @Test
    void extractGroupsMultiMultipleGroupsWithAndWithoutNested() {
        String xml = """
                <Root>
                  <Group>
                    <Key>a</Key>
                  </Group>
                  <Group>
                    <Key>b</Key>
                    <Container><Inner>skip</Inner></Container>
                  </Group>
                </Root>
                """;

        List<Map<String, List<String>>> groups = XmlParser.extractGroupsMulti(xml, "Group");

        assertEquals(2, groups.size());
        assertEquals(List.of("a"), groups.get(0).get("Key"));
        assertEquals(List.of("b"), groups.get(1).get("Key"));
    }

    // --- extractGroups: same resilience for single-value variant ---

    @Test
    void extractGroupsSkipsNestedElements() {
        String xml = """
                <Root>
                  <Group>
                    <Name>test</Name>
                    <Nested><Deep>skip</Deep></Nested>
                    <Value>kept</Value>
                  </Group>
                </Root>
                """;

        List<Map<String, String>> groups = XmlParser.extractGroups(xml, "Group");

        assertEquals(1, groups.size());
        assertEquals("test", groups.get(0).get("Name"));
        assertEquals("kept", groups.get(0).get("Value"));
        assertNull(groups.get(0).get("Nested"));
    }

    // --- extractPairsPerGroup ---

    @Test
    void extractPairsPerGroupBasic() {
        String xml = """
                <Root>
                  <Group>
                    <Leaf>text</Leaf>
                    <Wrapper>
                      <Pair>
                        <Key>color</Key>
                        <Val>red</Val>
                      </Pair>
                    </Wrapper>
                  </Group>
                </Root>
                """;

        List<Map<String, String>> pairs =
                XmlParser.extractPairsPerGroup(xml, "Group", "Pair", "Key", "Val");

        assertEquals(1, pairs.size());
        assertEquals("red", pairs.get(0).get("color"));
    }

    @Test
    void extractPairsPerGroupMultiplePairsPerGroup() {
        String xml = """
                <Root>
                  <Group>
                    <Wrapper>
                      <Rule><Name>prefix</Name><Value>images/</Value></Rule>
                      <Rule><Name>suffix</Name><Value>.jpg</Value></Rule>
                    </Wrapper>
                  </Group>
                </Root>
                """;

        List<Map<String, String>> pairs =
                XmlParser.extractPairsPerGroup(xml, "Group", "Rule", "Name", "Value");

        assertEquals(1, pairs.size());
        assertEquals("images/", pairs.get(0).get("prefix"));
        assertEquals(".jpg", pairs.get(0).get("suffix"));
    }

    @Test
    void extractPairsPerGroupMultipleGroups() {
        String xml = """
                <Root>
                  <Group>
                    <Tag><Key>env</Key><Val>prod</Val></Tag>
                  </Group>
                  <Group>
                    <Tag><Key>team</Key><Val>infra</Val></Tag>
                    <Tag><Key>cost</Key><Val>shared</Val></Tag>
                  </Group>
                </Root>
                """;

        List<Map<String, String>> pairs =
                XmlParser.extractPairsPerGroup(xml, "Group", "Tag", "Key", "Val");

        assertEquals(2, pairs.size());
        assertEquals(Map.of("env", "prod"), pairs.get(0));
        assertEquals(Map.of("team", "infra", "cost", "shared"), pairs.get(1));
    }

    @Test
    void extractPairsPerGroupEmptyWhenNoPairsFound() {
        String xml = """
                <Root>
                  <Group>
                    <Name>no-pairs-here</Name>
                  </Group>
                </Root>
                """;

        List<Map<String, String>> pairs =
                XmlParser.extractPairsPerGroup(xml, "Group", "Pair", "Key", "Value");

        assertEquals(1, pairs.size());
        assertTrue(pairs.get(0).isEmpty());
    }

    @Test
    void extractPairsPerGroupNullAndEmptyXml() {
        assertTrue(XmlParser.extractPairsPerGroup(null, "G", "P", "K", "V").isEmpty());
        assertTrue(XmlParser.extractPairsPerGroup("", "G", "P", "K", "V").isEmpty());
    }

    @Test
    void extractPairsPerGroupIndexAlignedWithExtractGroupsMulti() {
        String xml = """
                <Conf>
                  <QueueConfiguration>
                    <Queue>arn:q1</Queue>
                    <Event>s3:ObjectCreated:*</Event>
                  </QueueConfiguration>
                  <QueueConfiguration>
                    <Queue>arn:q2</Queue>
                    <Event>s3:ObjectRemoved:*</Event>
                    <Filter><S3Key>
                      <FilterRule><Name>prefix</Name><Value>logs/</Value></FilterRule>
                    </S3Key></Filter>
                  </QueueConfiguration>
                </Conf>
                """;

        var groups = XmlParser.extractGroupsMulti(xml, "QueueConfiguration");
        var filters = XmlParser.extractPairsPerGroup(xml, "QueueConfiguration",
                "FilterRule", "Name", "Value");

        assertEquals(2, groups.size());
        assertEquals(2, filters.size());
        assertTrue(filters.get(0).isEmpty());
        assertEquals("logs/", filters.get(1).get("prefix"));
    }
}
