package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@ApplicationScoped
public final class SpectrumS3Reader {

    private static final String SQLSTATE_AUTHORIZATION = "42501";
    private static final String SQLSTATE_DATA = "22000";
    private static final String SQLSTATE_INTERNAL = "XX000";

    private final S3Service s3Service;
    private final IamService iamService;

    public SpectrumS3Reader(S3Service s3Service, IamService iamService) {
        this.s3Service = s3Service;
        this.iamService = iamService;
    }

    public Stream<SpectrumRow> read(SpectrumExternalSchema schema, SpectrumExternalTable table) {
        Location location = Location.parse(table.location());
        try {
            s3Service.authorizeAnonymousListBucket(location.bucket());
            List<S3Object> objects = new ArrayList<>();
            String continuationToken = null;
            do {
                S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes(
                        location.bucket(), location.key(), "", 1000, continuationToken, null);
                objects.addAll(result.objects());
                continuationToken = result.isTruncated() ? result.nextContinuationToken() : null;
            } while (continuationToken != null);
            List<String> keys = objects.stream().map(S3Object::getKey).sorted(Comparator.naturalOrder()).toList();
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                    new RowIterator(location.bucket(), keys.iterator(), table), 0), false);
        } catch (AwsException exception) {
            throw mapAwsException(location, exception);
        }
    }

    private SpectrumReadException mapAwsException(Location location, AwsException exception) {
        String state = switch (exception.getErrorCode()) {
            case "AccessDenied", "Unauthorized", "Forbidden" -> SQLSTATE_AUTHORIZATION;
            default -> SQLSTATE_INTERNAL;
        };
        return new SpectrumReadException(state, "Unable to read Spectrum location " + location.uri(), exception);
    }

    private final class RowIterator implements Iterator<SpectrumRow> {
        private final String bucket;
        private final Iterator<String> keys;
        private final SpectrumExternalTable table;
        private BufferedReader reader;
        private SpectrumRow next;
        private boolean finished;
        private String currentKey;

        private RowIterator(String bucket, Iterator<String> keys, SpectrumExternalTable table) {
            this.bucket = bucket;
            this.keys = keys;
            this.table = table;
        }

        @Override
        public boolean hasNext() {
            if (next == null && !finished) {
                advance();
            }
            return next != null;
        }

        @Override
        public SpectrumRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            SpectrumRow result = next;
            next = null;
            return result;
        }

        private void advance() {
            try {
                while (true) {
                    if (reader == null) {
                        if (!keys.hasNext()) {
                            finished = true;
                            return;
                        }
                        currentKey = keys.next();
                        s3Service.authorizeAnonymousGetObject(bucket, currentKey);
                        S3Object object = s3Service.getObject(bucket, currentKey);
                        reader = new BufferedReader(new InputStreamReader(
                                new ByteArrayInputStream(object.getData()), StandardCharsets.UTF_8));
                        skipHeaders();
                    }
                    String record = readRecord(reader, table.quote(), table.escape());
                    if (record == null) {
                        reader.close();
                        reader = null;
                        continue;
                    }
                    if (!record.isEmpty()) {
                        next = convert(record, table);
                        return;
                    }
                }
            } catch (AwsException exception) {
                throw mapAwsException(new Location(bucket, currentKey), exception);
            } catch (IOException exception) {
                throw new SpectrumReadException(SQLSTATE_INTERNAL, "Unable to close Spectrum object", exception);
            }
        }

        private void skipHeaders() throws IOException {
            for (int i = 0; i < table.headerLines(); i++) {
                if (readRecord(reader, table.quote(), table.escape()) == null) {
                    return;
                }
            }
        }
    }

    private static SpectrumRow convert(String record, SpectrumExternalTable table) {
        List<String> fields = parseFields(record, table.delimiter(), table.quote(), table.escape());
        if (fields.size() != table.columns().size()) {
            throw new SpectrumReadException(SQLSTATE_DATA,
                    "Spectrum row has " + fields.size() + " fields, expected " + table.columns().size());
        }
        List<String> converted = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            String value = fields.get(i);
            converted.add(value.equals(table.nullValue()) ? null : convertValue(value, table.columns().get(i).type()));
        }
        return new SpectrumRow(converted);
    }

    private static String convertValue(String value, SpectrumColumn.Type type) {
        try {
            return switch (type) {
                case VARCHAR, CHAR -> value;
                case INTEGER -> Integer.toString(Integer.parseInt(value));
                case BIGINT -> Long.toString(Long.parseLong(value));
                case DECIMAL -> new BigDecimal(value).toString();
                case BOOLEAN -> parseBoolean(value);
                case DATE -> LocalDate.parse(value).toString();
                case TIMESTAMP -> parseTimestamp(value);
            };
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new SpectrumReadException(SQLSTATE_DATA, "Invalid " + type + " value", exception);
        }
    }

    private static String parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "false";
        }
        throw new SpectrumReadException(SQLSTATE_DATA, "Invalid BOOLEAN value");
    }

    private static String parseTimestamp(String value) {
        try {
            return LocalDateTime.parse(value).toString();
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value).toLocalDateTime().toString();
        }
    }

    private static List<String> parseFields(String record, char delimiter, char quote, char escape) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char current = record.charAt(i);
            if (current == escape && quoted && i + 1 < record.length()) {
                field.append(record.charAt(++i));
            } else if (current == quote) {
                if (quoted && i + 1 < record.length() && record.charAt(i + 1) == quote) {
                    field.append(quote);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == delimiter && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw new SpectrumReadException(SQLSTATE_DATA, "Unterminated quoted CSV field");
        }
        fields.add(field.toString());
        return fields;
    }

    private static String readRecord(BufferedReader reader, char quote, char escape) throws IOException {
        StringBuilder record = new StringBuilder();
        boolean quoted = false;
        int value;
        while ((value = reader.read()) != -1) {
            char current = (char) value;
            if (current == escape && !quoted) {
                record.append(current);
                int next = reader.read();
                if (next == -1) {
                    break;
                }
                record.append((char) next);
            } else if (current == quote) {
                quoted = !quoted;
                record.append(current);
            } else if (current == '\n' && !quoted) {
                break;
            } else if (current != '\r') {
                record.append(current);
            }
        }
        if (value == -1 && record.isEmpty()) {
            return null;
        }
        return record.toString();
    }

    private record Location(String bucket, String key) {
        private static Location parse(String uri) {
            if (uri == null || !uri.startsWith("s3://")) {
                throw new SpectrumReadException(SQLSTATE_INTERNAL, "Spectrum location must use s3://");
            }
            String path = uri.substring(5);
            int slash = path.indexOf('/');
            if (slash <= 0) {
                throw new SpectrumReadException(SQLSTATE_INTERNAL, "Spectrum location must include a bucket and key");
            }
            return new Location(path.substring(0, slash), path.substring(slash + 1));
        }

        private String uri() {
            return "s3://" + bucket + "/" + key;
        }
    }
}
