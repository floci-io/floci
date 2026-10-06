package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class S3SelectService {

    private static final Logger LOG = Logger.getLogger(S3SelectService.class);

    private static final Pattern S3OBJECT_PATTERN =
            Pattern.compile("\\bS3Object\\b", Pattern.CASE_INSENSITIVE);

    // Records are written out in events of about this size, so the response never holds them all.
    private static final int RECORDS_EVENT_SIZE = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final FlociDuckClient duckClient;

    @Inject
    public S3SelectService(ObjectMapper objectMapper, FlociDuckClient duckClient) {
        this.objectMapper = objectMapper;
        this.duckClient = duckClient;
    }

    /** A SelectObjectContent response, written as an AWS event stream. */
    @FunctionalInterface
    public interface EventStream {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Answers a SelectObjectContent request over {@code read}, an object and an open stream over the
     * body that matches it, and takes over closing {@code read}. A query floci-duck runs (Parquet, and
     * JSON or CSV with a header row while it is available) reads the object itself, so it is answered
     * here. The others are evaluated as the returned stream is written, a record at a time, so the
     * object is never held in memory. A request that cannot be answered fails here, before any of the
     * response is written; one that fails while it is written, such as on a record over S3 Select's
     * limit, ends the stream with a request-level error message after the records selected so far.
     */
    public EventStream select(S3Service.ObjectRead read, String requestXml) {
        try {
            S3Object object = read.object();
            String expression = XmlParser.extractFirst(requestXml, "Expression", "");
            String fileHeaderInfo = XmlParser.extractFirst(requestXml, "FileHeaderInfo", "NONE");
            String inputType = detectType(requestXml, "InputSerialization");
            String detectedOutput = detectType(requestXml, "OutputSerialization");
            String outputFormat = detectedOutput != null ? detectedOutput : "CSV";

            if (isParquet(object)) {
                return answered(read, selectParquet(object, expression, outputFormat), object.getSize());
            }
            if (duckClient.isAvailable() && canUseDuck(inputType, fileHeaderInfo)) {
                return answered(read, selectViaDuck(object, expression, inputType, outputFormat), object.getSize());
            }
            boolean evaluated = "CSV".equals(inputType) || "JSON".equals(inputType);
            S3SelectEvaluator.Query query = evaluated ? S3SelectEvaluator.parse(expression) : null;
            return out -> {
                try (S3Service.ObjectRead source = read) {
                    CountingInputStream body = new CountingInputStream(source.body());
                    RecordsWriter records = new RecordsWriter(out);
                    try {
                        if (query == null) {
                            // Not a statement the evaluator reads: the object is returned as it is.
                            records.copy(body);
                        } else {
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(body, StandardCharsets.UTF_8));
                            if ("CSV".equals(inputType)) {
                                S3SelectEvaluator.evaluateCsv(reader, query, fileHeaderInfo, outputFormat,
                                        records::record);
                            } else {
                                S3SelectEvaluator.evaluateJson(reader, query, objectMapper, outputFormat,
                                        records::record);
                            }
                        }
                    } catch (AwsException e) {
                        records.fail(e);
                        return;
                    }
                    // A LIMIT can stop the query before the end of the object; what was read is what was scanned.
                    records.finish(body.count());
                }
            };
        } catch (RuntimeException e) {
            closeQuietly(read);
            throw e;
        }
    }

    /** A response worked out in full already; the object's stream is not needed for it. */
    private static EventStream answered(S3Service.ObjectRead read, String result, long bytesScanned) {
        closeQuietly(read);
        try {
            ByteArrayOutputStream events = new ByteArrayOutputStream();
            RecordsWriter records = new RecordsWriter(events);
            records.copy(new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8)));
            records.finish(bytesScanned);
            byte[] encoded = events.toByteArray();
            return out -> out.write(encoded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode an S3 Select response", e);
        }
    }

    private static void closeQuietly(S3Service.ObjectRead read) {
        try {
            read.close();
        } catch (IOException e) {
            LOG.debugv("Could not close an S3 Select source: {0}", e.getMessage());
        }
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
        List<String> children = XmlParser.childElementNames(requestXml, sectionTag);
        if (children.contains("CSV")) return "CSV";
        if (children.contains("JSON")) return "JSON";
        if (children.contains("Parquet")) return "PARQUET";
        return null;
    }

    /**
     * Writes result records as Records events of about {@link #RECORDS_EVENT_SIZE} bytes, then the
     * Stats and End events. S3 Select may split a record across Records events; records written one
     * at a time are kept whole. A response always carries a Records event, even an empty one.
     */
    private static final class RecordsWriter {

        private final OutputStream out;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private long bytesReturned;
        private boolean recordsWritten;

        RecordsWriter(OutputStream out) {
            this.out = out;
        }

        void record(String record) throws IOException {
            if (record.length() > S3SelectEvaluator.MAX_RECORD_CHARS) {
                throw S3SelectEvaluator.overMaxRecordSize();
            }
            pending.writeBytes((record + "\n").getBytes(StandardCharsets.UTF_8));
            if (pending.size() >= RECORDS_EVENT_SIZE) {
                flush();
            }
        }

        void copy(InputStream body) throws IOException {
            byte[] buffer = new byte[RECORDS_EVENT_SIZE];
            for (int read = body.readNBytes(buffer, 0, buffer.length); read > 0;
                 read = body.readNBytes(buffer, 0, buffer.length)) {
                pending.write(buffer, 0, read);
                flush();
            }
        }

        void finish(long bytesScanned) throws IOException {
            if (pending.size() > 0 || !recordsWritten) {
                flush();
            }
            LinkedHashMap<String, String> statsHeaders = new LinkedHashMap<>();
            statsHeaders.put(":message-type", "event");
            statsHeaders.put(":event-type", "Stats");
            statsHeaders.put(":content-type", "text/xml");
            String statsXml = new XmlBuilder()
                    .start("Stats")
                    .elem("BytesScanned", bytesScanned)
                    .elem("BytesProcessed", bytesScanned)
                    .elem("BytesReturned", bytesReturned)
                    .end("Stats")
                    .build();
            out.write(AwsEventStreamEncoder.encodeMessage(statsHeaders, statsXml.getBytes(StandardCharsets.UTF_8)));

            LinkedHashMap<String, String> endHeaders = new LinkedHashMap<>();
            endHeaders.put(":message-type", "event");
            endHeaders.put(":event-type", "End");
            out.write(AwsEventStreamEncoder.encodeMessage(endHeaders, new byte[0]));
        }

        /**
         * Ends the stream with a request-level error message, after the records selected so far, as
         * S3 Select reports an error once the response has begun.
         */
        void fail(AwsException error) throws IOException {
            if (pending.size() > 0) {
                flush();
            }
            LinkedHashMap<String, String> errorHeaders = new LinkedHashMap<>();
            errorHeaders.put(":error-code", error.getErrorCode());
            errorHeaders.put(":error-message", error.getMessage());
            errorHeaders.put(":message-type", "error");
            out.write(AwsEventStreamEncoder.encodeMessage(errorHeaders, new byte[0]));
        }

        private void flush() throws IOException {
            byte[] payload = pending.toByteArray();
            pending.reset();
            LinkedHashMap<String, String> recordsHeaders = new LinkedHashMap<>();
            recordsHeaders.put(":message-type", "event");
            recordsHeaders.put(":event-type", "Records");
            recordsHeaders.put(":content-type", "application/octet-stream");
            out.write(AwsEventStreamEncoder.encodeMessage(recordsHeaders, payload));
            bytesReturned += payload.length;
            recordsWritten = true;
        }
    }

    /** Counts the bytes read from the object, so the Stats event reports what the query scanned. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
