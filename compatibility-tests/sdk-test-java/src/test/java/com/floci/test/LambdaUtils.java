package com.floci.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shared Lambda deployment-package helpers for tests.
 */
public final class LambdaUtils {

    private LambdaUtils() {}

    /**
     * ZIP containing a Node.js handler that greets by name and echoes the event.
     */
    public static byte[] handlerZip() {
        String code = """
                exports.handler = async (event) => {
                    const name = (event && event.name) ? event.name : 'World';
                    console.log('[handler] invoked with event:', JSON.stringify(event));
                    console.log('[handler] resolved name:', name);
                    const response = {
                        statusCode: 200,
                        body: JSON.stringify({ message: `Hello, ${name}!`, input: event })
                    };
                    console.log('[handler] returning response:', JSON.stringify(response));
                    return response;
                };
                """;
        return createZip("index.js", code);
    }

    /**
     * ZIP containing a Ruby handler that greets by name.
     */
    public static byte[] rubyZip() {
        String code = """
                def lambda_handler(event:, context:)
                  name = event['name'] || 'World'
                  { statusCode: 200, body: "Hello, #{name}!" }
                end
                """;
        return createZip("lambda_function.rb", code);
    }

    /**
     * ZIP containing a bootstrap shell script for provided runtimes.
     */
    public static byte[] providedRuntimeZip() {
        String bootstrap = """
                #!/bin/sh
                ENDPOINT="http://${AWS_LAMBDA_RUNTIME_API}/2018-06-01/runtime"
                while true; do
                  HEADERS=$(mktemp)
                  curl -sS -D "$HEADERS" -o /tmp/event.json "${ENDPOINT}/invocation/next"
                  REQUEST_ID=$(grep -i 'lambda-runtime-aws-request-id' "$HEADERS" | tr -d '\\r' | awk '{print $2}')
                  curl -sS -X POST "${ENDPOINT}/invocation/${REQUEST_ID}/response" \\
                    -H 'Content-Type: application/json' \\
                    -d '"hello from provided runtime"'
                  rm -f "$HEADERS"
                done
                """;
        return createZip("bootstrap", bootstrap);
    }

    /**
     * ZIP containing a Node.js handler that always reports every SQS message as a batch item
     * failure. Used to test {@code ReportBatchItemFailures} ESM behaviour.
     */
    public static byte[] batchItemFailuresZip() {
        String code = """
                exports.handler = async (event) => {
                    const failures = (event.Records || []).map(r => ({
                        itemIdentifier: r.messageId
                    }));
                    console.log('[esm-failures] reporting failures:', JSON.stringify(failures));
                    return { batchItemFailures: failures };
                };
                """;
        return createZip("index.js", code);
    }

    /**
     * Minimal valid ZIP containing a stub index.js.
     */
    public static byte[] minimalZip() {
        String code = """
                exports.handler = async (event) => {
                    console.log('[esm-handler] invoked with event:', JSON.stringify(event));
                    return { statusCode: 200, body: 'ok' };
                };
                """;
        return createZip("index.js", code);
    }

    /**
     * ZIP containing a Node.js handler that checks whether a file at a deeply nested
     * long path (> 100 chars) exists inside the container.
     *
     * Used to test that zip extraction correctly preserves long file paths.
     * Regression test for: https://github.com/floci-io/floci/issues/232
     *
     * The nested file path is intentionally > 99 characters to exceed the legacy
     * POSIX USTAR tar header name field limit, which is where truncation occurred.
     */
    public static byte[] longPathZip() {
        // Relative path is 117 chars — well over the 99-char USTAR limit
        String longPath = "vendor/bundle/ruby/3.3.0/gems/bundler-4.0.3/lib/bundler/vendor/thor/lib/thor/core_ext/hash_with_indifferent_access.txt";
        String handler = """
                const fs = require('fs');
                exports.handler = async () => {
                    const longPath = '/var/task/%s';
                    const exists = fs.existsSync(longPath);
                    return { exists, pathLength: longPath.length };
                };
                """.formatted(longPath);
        String fileContent = "present";

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write(handler.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry(longPath));
                zos.write(fileContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build long-path ZIP", e);
        }
    }

    private static byte[] createZip(String filename, String content) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry(filename));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build ZIP for " + filename, e);
        }
    }
}
