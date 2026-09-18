package io.github.hectorvent.floci.services.ses.imports;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.github.hectorvent.floci.services.ses.model.ImportJob;

import java.io.BufferedReader;

/**
 * Builds the reader for an import job's DataFormat. Takes the mapper rather than a ready-made
 * {@link ObjectReader} on purpose: reading a JSON line strictly is a requirement of the format, not
 * a caller's preference, so the setting lives here instead of leaking to whoever wires this up,
 * where a plain reader would compile and quietly accept malformed lines.
 */
public final class RecordReaderFactory {

    /**
     * FAIL_ON_TRAILING_TOKENS so a line like {@code {"emailAddress":"x"} garbage} is a malformed
     * record rather than a silently accepted object, as the JSON protocol controllers read their
     * bodies. Built once: an import reads thousands of lines through it.
     */
    private final ObjectReader lineReader;

    public RecordReaderFactory(ObjectMapper objectMapper) {
        this.lineReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** CSV is parsed without Jackson, so only the JSON reader is handed the line reader. */
    public RecordReader forJob(ImportJob job, BufferedReader source) {
        return ImportJob.FORMAT_JSON.equals(job.getDataFormat())
                ? new JsonLineReader(source, job, lineReader)
                : new CsvRecordReader(source, job);
    }
}
