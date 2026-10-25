package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class RequestBodyReader {

    private static final Logger LOG = Logger.getLogger(RequestBodyReader.class);
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final String BUFFERED_BODY_PROPERTY =
            "io.github.hectorvent.floci.services.iam.RequestBodyReader.bufferedBody";

    private RequestBodyReader() {
    }

    static byte[] buffer(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_BODY_PROPERTY);
        if (cached instanceof byte[] bytes) {
            return bytes;
        }
        InputStream in = ctx.getEntityStream();
        byte[] body;
        if (in == null) {
            body = new byte[0];
        } else {
            try {
                body = in.readAllBytes();
            } catch (IOException e) {
                LOG.debugv(e, "Failed to buffer request body for IAM enforcement");
                body = new byte[0];
            }
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        ctx.setProperty(BUFFERED_BODY_PROPERTY, body);
        return body;
    }

    static boolean isForm(ContainerRequestContext ctx) {
        MediaType mt = ctx.getMediaType();
        return mt != null
                && "application".equalsIgnoreCase(mt.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mt.getSubtype());
    }

    public static String formField(ContainerRequestContext ctx, String key) {
        if ("GET".equalsIgnoreCase(ctx.getMethod()) && IamActionRegistry.isQueryRequest(ctx)) {
            return ctx.getUriInfo().getQueryParameters().getFirst(key);
        }
        if (!isForm(ctx)) {
            return null;
        }
        MediaType mt = ctx.getMediaType();
        byte[] body = buffer(ctx);
        if (body.length == 0) {
            return null;
        }
        Charset charset = resolveCharset(mt);
        String form = new String(body, charset);
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            String pairKey = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(URLDecoder.decode(pairKey, charset))) {
                continue;
            }
            return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), charset);
        }
        return null;
    }

    static String documentField(ContainerRequestContext ctx, ObjectMapper objectMapper, String key) {
        MediaType mt = ctx.getMediaType();
        if (mt == null || !"application".equalsIgnoreCase(mt.getType())
                || mt.getSubtype() == null || (!isCbor(mt) && !mt.getSubtype().startsWith("x-amz-json"))) {
            return null;
        }
        byte[] body = buffer(ctx);
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode value = readTree(body, mt, objectMapper).path(key);
            return value.isTextual() ? value.asText() : null;
        } catch (IOException e) {
            LOG.debugv(e, "Failed to parse request document for IAM enforcement");
            return null;
        }
    }

    /** Both CBOR wire protocols carry the same document members as JSON requests. */
    static boolean isCbor(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && ("cbor".equalsIgnoreCase(mediaType.getSubtype())
                        || "x-amz-cbor-1.1".equalsIgnoreCase(mediaType.getSubtype()));
    }

    static JsonNode readTree(byte[] body, MediaType mediaType, ObjectMapper json) throws IOException {
        return (isCbor(mediaType) ? CBOR_MAPPER : json).readTree(body);
    }

    private static Charset resolveCharset(MediaType mt) {
        String name = mt.getParameters().get("charset");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
