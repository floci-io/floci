package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SsrfProtection;
import io.floci.aws.core.common.ValidatingDnsResolver;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded HTTP transport for unsigned data-source endpoints, including local development services. */
@ApplicationScoped
class HttpDataSourceTransport implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpDataSourceTransport.class);
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private final CloseableHttpClient client;
    private final Duration timeout;
    // System DNS may ignore interruption. Bound both waiting callers and outstanding lookups.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
        Thread thread = new Thread(runnable, "appsync-http-request");
        thread.setDaemon(true);
        return thread;
    });

    HttpDataSourceTransport() {
        this(client(checkedResolver(SystemDefaultDnsResolver.INSTANCE)));
    }

    HttpDataSourceTransport(CloseableHttpClient client) {
        this(client, Duration.ofSeconds(30));
    }

    HttpDataSourceTransport(CloseableHttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    static CloseableHttpClient client(DnsResolver resolver) {
        return client(resolver, null);
    }

    static CloseableHttpClient client(DnsResolver resolver, SSLContext sslContext) {
        PoolingHttpClientConnectionManagerBuilder connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(30)).build())
                .setMaxConnTotal(32).setMaxConnPerRoute(8);
        if (sslContext != null) {
            connections.setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic());
        }
        return HttpClients.custom()
                .setConnectionManager(connections.build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(30)).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().build();
    }

    static DnsResolver checkedResolver(DnsResolver delegate) {
        return new ValidatingDnsResolver(delegate, (host, addresses) -> {
            try {
                SsrfProtection.rejectMetadataAddresses(addresses, host);
                // Keep this additional metadata exclusion local to AppSync's unsigned HTTP transport.
                for (InetAddress address : addresses) {
                    byte[] bytes = InetAddress.getByAddress(address.getAddress()).getAddress();
                    if (bytes.length == 4 && bytes[0] == 100 && bytes[1] == 100 && bytes[2] == 100
                            && Byte.toUnsignedInt(bytes[3]) == 200) {
                        throw new UnknownHostException("HTTP data source endpoint resolves to a metadata address");
                    }
                }
            } catch (IOException e) {
                UnknownHostException failure = new UnknownHostException("HTTP data source endpoint resolves to a metadata address");
                failure.initCause(e);
                throw failure;
            }
        });
    }

    Map<String, Object> send(HttpDataSourceRequest request) {
        byte[] body = request.body();
        if (body.length > MAX_BYTES) {
            throw new AwsException("HttpException", "HTTP data source request exceeds 5 MB", 400);
        }
        HttpUriRequestBase outgoing = new HttpUriRequestBase(request.method(), request.uri());
        request.headers().forEach(outgoing::addHeader);
        if (body.length > 0 || "POST".equals(request.method()) || "PUT".equals(request.method()) || "PATCH".equals(request.method())) {
            outgoing.setEntity(new ByteArrayEntity(body, null));
        }
        Future<Map<String, Object>> pending;
        try {
            pending = workers.submit(() -> execute(outgoing));
        } catch (RejectedExecutionException e) {
            throw new AwsException("HttpException", "HTTP data source concurrency limit reached", 500);
        }
        boolean completed = false;
        try {
            Map<String, Object> result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            completed = true;
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("HttpException", "HTTP data source request was interrupted", 500);
        } catch (TimeoutException e) {
            LOG.debugv("HTTP data source deadline exceeded: method={0}, host={1}", request.method(), request.uri().getHost());
            throw new AwsException("HttpException", "HTTP data source request deadline exceeded", 500);
        } catch (ExecutionException e) {
            // Exception messages and URLs can contain credentials or query values. Log only the type.
            LOG.debugv("HTTP data source failed: method={0}, host={1}, cause={2}", request.method(),
                    request.uri().getHost(), e.getCause().getClass().getSimpleName());
            throw new AwsException("HttpException", "HTTP data source request could not complete", 500);
        } finally {
            if (!completed) {
                outgoing.cancel();
                pending.cancel(true);
            }
        }
    }

    private Map<String, Object> execute(HttpUriRequestBase outgoing) throws IOException {
        return client.execute(outgoing, response -> {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Header header : response.getHeaders()) {
                headers.merge(header.getName(), header.getValue(), (left, right) -> left + "," + right);
            }
            byte[] bytes;
            if (response.getEntity() == null) {
                bytes = new byte[0];
            } else {
                try (InputStream stream = response.getEntity().getContent()) {
                    bytes = stream.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) {
                        outgoing.cancel();
                        throw new IOException("HTTP data source response exceeds 5 MB");
                    }
                }
            }
            return Map.of("statusCode", response.getCode(), "headers", headers, "body",
                    new String(bytes, responseCharset(headers.get("content-type"))));
        });
    }

    private static Charset responseCharset(String contentType) {
        try {
            return ContentType.getCharset(ContentType.parseLenient(contentType), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }
}
