package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.floci.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class S3SelectService {

    private static final Pattern S3OBJECT_PATTERN =
            Pattern.compile("\\bS3Object\\b", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final FlociDuckClient duckClient;

    @Inject
    public S3SelectService(ObjectMapper objectMapper, FlociDuckClient duckClient) {
        this.objectMapper = objectMapper;
        this.duckClient = duckClient;
    }

    public byte[] select(S3Object object, String requestXml) {
        String expression = XmlParser.extractFirst(requestXml, "Expression", "");
        String fileHeaderInfo = XmlParser.extractFirst(requestXml, "FileHeaderInfo", "NONE");
        String inputType = detectType(requestXml, "InputSerialization");
        String outputFormat = detectType(requestXml, "OutputSerialization");
        if (outputFormat == null) outputFormat = "CSV";

        byte[] rawData = object.getData();
        if (rawData == null) return new byte[0];
        long bytesScanned = rawData.length;

        String result;
        if (isParquet(object)) {
            result = selectParquet(object, expression, outputFormat);
        } else if (duckClient.isAvailable() && canUseDuck(inputType, fileHeaderInfo)) {
            result = selectViaDuck(object, expression, inputType, outputFormat);
        } else if ("CSV".equals(inputType)) {
            String content = new String(rawData, StandardCharsets.UTF_8);
            result = S3SelectEvaluator.evaluateCsv(content, expression, fileHeaderInfo, outputFormat);
        } else if ("JSON".equals(inputType)) {
            String content = new String(rawData, StandardCharsets.UTF_8);
            result = S3SelectEvaluator.evaluateJson(content, expression, objectMapper, outputFormat);
        } else {
            result = new String(rawData, StandardCharsets.UTF_8);
        }

        long bytesReturned = result.getBytes(StandardCharsets.UTF_8).length;
        return encodeEventStream(result, bytesScanned, bytesReturned);
    }

    // ── Duck delegation ────────────────────────────────────────────────────

    /**
     * Duck handles CSV with USE headers (named columns) and all JSON.
     * CSV NONE/IGNORE use _N positional refs that DuckDB doesn't natively support,
     * so those fall back to the Java evaluator.
     */
    private static boolean canUseDuck(String inputType, String fileHeaderInfo) {
        if ("JSON".equals(inputType)) {
            return true;
        }
        return "CSV".equals(inputType) && "USE".equals(fileHeaderInfo);
    }

    private String selectViaDuck(S3Object object, String expression, String inputType,
                                 String outputFormat) {
        String s3Uri = "s3://" + object.getBucketName() + "/" + object.getKey();
        String readFn = "JSON".equals(inputType)
                ? "read_json_auto('" + s3Uri + "')"
                : "read_csv('" + s3Uri + "', header=true, null_padding=true)";
        String duckSql = S3OBJECT_PATTERN.matcher(expression)
                .replaceAll(Matcher.quoteReplacement(readFn));
        List<Map<String, Object>> rows = duckClient.query(duckSql, null);
        return S3SelectEvaluator.formatDuckRows(rows, outputFormat, objectMapper);
    }

    // ── Parquet delegation ─────────────────────────────────────────────────

    private static boolean isParquet(S3Object object) {
        String ct = object.getContentType();
        if (ct != null && ct.toLowerCase().contains("parquet")) return true;
        String key = object.getKey();
        return key != null && key.toLowerCase().endsWith(".parquet");
    }

    private String selectParquet(S3Object object, String expression, String outputFormat) {
        String s3Uri = "s3://" + object.getBucketName() + "/" + object.getKey();
        String duckSql = S3OBJECT_PATTERN.matcher(expression)
                .replaceAll(Matcher.quoteReplacement("read_parquet('" + s3Uri + "')"));

        List<Map<String, Object>> rows = duckClient.query(duckSql, null);
        return S3SelectEvaluator.formatDuckRows(rows, outputFormat, objectMapper);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String detectType(String requestXml, String sectionTag) {
        String openTag = "<" + sectionTag + ">";
        int start = requestXml.indexOf(openTag);
        if (start < 0) return null;
        int end = requestXml.indexOf("</" + sectionTag + ">", start);
        String section = end > start ? requestXml.substring(start, end) : requestXml.substring(start);
        if (section.contains("<CSV")) return "CSV";
        if (section.contains("<JSON")) return "JSON";
        if (section.contains("<Parquet")) return "PARQUET";
        return null;
    }

    private byte[] encodeEventStream(String payload, long bytesScanned, long bytesReturned) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            LinkedHashMap<String, String> recordsHeaders = new LinkedHashMap<>();
            recordsHeaders.put(":message-type", "event");
            recordsHeaders.put(":event-type", "Records");
            recordsHeaders.put(":content-type", "application/octet-stream");
            baos.write(AwsEventStreamEncoder.encodeMessage(recordsHeaders, payload.getBytes(StandardCharsets.UTF_8)));

            LinkedHashMap<String, String> statsHeaders = new LinkedHashMap<>();
            statsHeaders.put(":message-type", "event");
            statsHeaders.put(":event-type", "Stats");
            statsHeaders.put(":content-type", "text/xml");
            String statsXml = "<Stats><BytesScanned>" + bytesScanned + "</BytesScanned>"
                    + "<BytesProcessed>" + bytesScanned + "</BytesProcessed>"
                    + "<BytesReturned>" + bytesReturned + "</BytesReturned></Stats>";
            baos.write(AwsEventStreamEncoder.encodeMessage(statsHeaders, statsXml.getBytes(StandardCharsets.UTF_8)));

            LinkedHashMap<String, String> endHeaders = new LinkedHashMap<>();
            endHeaders.put(":message-type", "event");
            endHeaders.put(":event-type", "End");
            baos.write(AwsEventStreamEncoder.encodeMessage(endHeaders, new byte[0]));

            return baos.toByteArray();
        } catch (Exception e) {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }
}
