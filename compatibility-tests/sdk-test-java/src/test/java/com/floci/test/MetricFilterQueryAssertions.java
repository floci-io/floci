package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** The current SDK selects JSON. Exercise the legacy AWS Query contract without an older SDK dependency. */
final class MetricFilterQueryAssertions {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private MetricFilterQueryAssertions() {}

    static void assertSeries(String namespace, String metric, Instant minute,
                             List<Dimension> dimensions, JsonNode expected) throws Exception {
        boolean present = expected != null && expected.has("Sum");
        Map<String, String> params = window(minute);
        params.put("Namespace", namespace);
        params.put("MetricName", metric);
        params.put("Period", "60");
        params.put("Statistics.member.1", "Sum");
        params.put("Statistics.member.2", "SampleCount");
        dimensions(params, "Dimensions", dimensions);
        Document stats = query("GetMetricStatistics", params);
        NodeList points = stats.getElementsByTagNameNS("*", "Timestamp");
        assertThat(points.getLength()).isEqualTo(present ? 1 : 0);
        if (present) {
            assertThat(Instant.parse(points.item(0).getTextContent())).isEqualTo(minute);
            assertThat(Double.parseDouble(text(stats.getDocumentElement(), "Sum"))).isEqualTo(expected.get("Sum").asDouble());
            assertThat(Double.parseDouble(text(stats.getDocumentElement(), "SampleCount")))
                    .isEqualTo(expected.get("SampleCount").asDouble());
            assertThat(text(stats.getDocumentElement(), "Unit")).isEqualTo("Count");
        }
        for (String stat : List.of("Sum", "SampleCount")) {
            params = window(minute);
            String prefix = "MetricDataQueries.member.1.";
            params.put(prefix + "Id", "probe");
            params.put(prefix + "ReturnData", "true");
            params.put(prefix + "MetricStat.Stat", stat);
            params.put(prefix + "MetricStat.Period", "60");
            params.put(prefix + "MetricStat.Metric.Namespace", namespace);
            params.put(prefix + "MetricStat.Metric.MetricName", metric);
            dimensions(params, prefix + "MetricStat.Metric.Dimensions", dimensions);
            Document data = query("GetMetricData", params);
            assertThat(text(data.getDocumentElement(), "Id")).isEqualTo("probe");
            assertThat(text(data.getDocumentElement(), "StatusCode")).isEqualTo("Complete");
            Element values = (Element) data.getElementsByTagNameNS("*", "Values").item(0);
            Element times = (Element) data.getElementsByTagNameNS("*", "Timestamps").item(0);
            assertThat(values.getElementsByTagNameNS("*", "member").getLength()).isEqualTo(present ? 1 : 0);
            assertThat(times.getElementsByTagNameNS("*", "member").getLength()).isEqualTo(present ? 1 : 0);
            if (present) {
                assertThat(Double.parseDouble(text(values, "member"))).isEqualTo(expected.get(stat).asDouble());
                assertThat(Instant.parse(text(times, "member"))).isEqualTo(minute);
            }
        }
    }

    private static Map<String, String> window(Instant minute) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("StartTime", minute.toString());
        params.put("EndTime", minute.plusSeconds(60).toString());
        return params;
    }

    private static void dimensions(Map<String, String> params, String prefix, List<Dimension> dimensions) {
        for (int i = 0; i < dimensions.size(); i++) {
            params.put(prefix + ".member." + (i + 1) + ".Name", dimensions.get(i).name());
            params.put(prefix + ".member." + (i + 1) + ".Value", dimensions.get(i).value());
        }
    }

    private static String text(Element element, String name) {
        return element.getElementsByTagNameNS("*", name).item(0).getTextContent();
    }

    private static Document query(String action, Map<String, String> params) throws Exception {
        params.put("Action", action);
        params.put("Version", "2010-08-01");
        String body = params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest signed = Aws4Signer.create().sign(SdkHttpFullRequest.builder().uri(TestFixtures.endpoint())
                .method(SdkHttpMethod.POST).putHeader("Content-Type", "application/x-www-form-urlencoded")
                .contentStreamProvider(() -> new ByteArrayInputStream(bytes)).build(),
                Aws4SignerParams.builder().awsCredentials(AwsBasicCredentials.create("test", "test"))
                        .signingRegion(Region.US_EAST_1).signingName("monitoring").build());
        HttpRequest.Builder request = HttpRequest.newBuilder(TestFixtures.endpoint()).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // This independent SDK module cannot import root XmlParser. Mirror its hardened JDK DOM configuration.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                response.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo(action + "Response");
        assertThat(document.getDocumentElement().getNamespaceURI()).isEqualTo("http://monitoring.amazonaws.com/doc/2010-08-01/");
        return document;
    }
}
