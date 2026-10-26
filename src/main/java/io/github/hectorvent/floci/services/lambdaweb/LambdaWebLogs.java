package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers the log events of Web Function environments. Each event goes to the stream of its
 * environment in the {@code .local} companion of the revision's log group in the region the
 * environment serves, and to the same stream with an {@code -<account>-<region>} suffix in the log
 * group itself in the home region, where AWS centralizes the regions an endpoint serves. A group or
 * stream is created when its first event arrives. The {@code platform.*} events below the
 * revision's {@code systemLogLevel} are dropped.
 */
@ApplicationScoped
public class LambdaWebLogs {

    private static final Logger LOG = Logger.getLogger(LambdaWebLogs.class);
    static final int MAX_QUEUED_EVENTS = 10_000;
    private static final int MAX_BATCH_EVENTS = 1_000;
    private static final List<String> SYSTEM_LEVELS = List.of("DEBUG", "INFO", "WARN");
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String LOGS_DROPPED_REASON =
            "Some logs were dropped because the downstream consumer is slower than the logs production rate";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CloudWatchLogsService logs;
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public LambdaWebLogs(CloudWatchLogsService logs) {
        this.logs = logs;
    }

    public Sink open(String accountId, String region, String homeRegion, String logGroup, String logStream,
                     String systemLogLevel) {
        return new Sink(accountId, region, SYSTEM_LEVELS.indexOf(systemLogLevel), List.of(
                new Destination(region, logGroup + ".local", logStream),
                new Destination(homeRegion, logGroup, logStream + "-" + accountId + "-" + region)));
    }

    /** A time as AWS writes it in a platform event, without the trailing zeros of the fraction. */
    static String timestamp(Instant at, int fractionDigits) {
        long fraction = at.getNano() / (long) Math.pow(10, 9 - fractionDigits);
        String digits = String.format("%0" + fractionDigits + "d", fraction).replaceFirst("0+$", "");
        return SECONDS.format(at) + (digits.isEmpty() ? "" : "." + digits) + "Z";
    }

    @PreDestroy
    void shutdown() {
        writers.shutdownNow();
    }

    private record Destination(String region, String logGroup, String logStream) {
    }

    private record Event(long timestamp, String message) {
    }

    /**
     * The events of one environment. {@link #accept} only queues, because the app blocks on its
     * output while the Docker stream that carries it waits. Past {@code MAX_QUEUED_EVENTS} events are dropped.
     */
    public final class Sink {
        private final String accountId;
        private final String region;
        private final int systemLevel;
        private final List<Destination> destinations;
        private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queued = new AtomicInteger();
        private final AtomicBoolean writing = new AtomicBoolean();
        private int droppedRecords;
        private long droppedBytes;

        private Sink(String accountId, String region, int systemLevel, List<Destination> destinations) {
            this.accountId = accountId;
            this.region = region;
            this.systemLevel = systemLevel;
            this.destinations = destinations;
        }

        public void accept(String message) {
            accept(System.currentTimeMillis(), message);
        }

        void accept(Instant at, String message) {
            accept(at.toEpochMilli(), message);
        }

        /** A {@code platform.*} event, unless its level is below {@code systemLogLevel}. */
        public void platform(Instant at, String level, String type, ObjectNode record) {
            if (SYSTEM_LEVELS.indexOf(level) < systemLevel) {
                return;
            }
            accept(at.toEpochMilli(), envelope(at, level, type, record));
        }

        private String envelope(Instant at, String level, String type, ObjectNode record) {
            ObjectNode event = JSON.createObjectNode();
            event.put("timestamp", timestamp(at, 3));
            event.put("level", level);
            event.put("type", type);
            event.put("region", region);
            event.set("record", record);
            return event.toString();
        }

        /** After a drop, the next event that fits is preceded by a {@code platform.logsDropped} event, as on AWS. */
        private synchronized void accept(long timestamp, String message) {
            boolean reportDrop = droppedRecords > 0 && SYSTEM_LEVELS.indexOf("WARN") >= systemLevel;
            if (queued.get() + (reportDrop ? 2 : 1) > MAX_QUEUED_EVENTS) {
                droppedRecords++;
                droppedBytes += message.getBytes(StandardCharsets.UTF_8).length;
                LOG.debugv("Dropped a log event of {0}: too many are waiting", destinations.getFirst().logStream());
                return;
            }
            if (reportDrop) {
                ObjectNode record = JSON.createObjectNode();
                record.put("reason", LOGS_DROPPED_REASON);
                record.put("droppedRecords", droppedRecords);
                record.put("droppedBytes", droppedBytes);
                Instant at = Instant.ofEpochMilli(timestamp);
                enqueue(timestamp, envelope(at, "WARN", "platform.logsDropped", record));
            }
            droppedRecords = 0;
            droppedBytes = 0;
            enqueue(timestamp, message);
            schedule();
        }

        private void enqueue(long timestamp, String message) {
            queued.incrementAndGet();
            queue.add(new Event(timestamp, message));
        }

        private void schedule() {
            if (!writing.compareAndSet(false, true)) {
                return;
            }
            try {
                writers.execute(this::write);
            } catch (RuntimeException e) {
                writing.set(false);
                LOG.debugv(e, "Could not deliver the log events of {0}", destinations.getFirst().logStream());
            }
        }

        private void write() {
            try {
                List<Map<String, Object>> batch = new ArrayList<>();
                Event event;
                while ((event = queue.poll()) != null) {
                    queued.decrementAndGet();
                    batch.add(Map.of("timestamp", event.timestamp(), "message", event.message()));
                    if (batch.size() == MAX_BATCH_EVENTS) {
                        put(batch);
                        batch = new ArrayList<>();
                    }
                }
                if (!batch.isEmpty()) {
                    put(batch);
                }
            } finally {
                writing.set(false);
                if (!queue.isEmpty()) {
                    schedule();
                }
            }
        }

        private void put(List<Map<String, Object>> batch) {
            for (Destination destination : destinations) {
                try {
                    putTo(destination, batch);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not deliver {0} log events to {1}", batch.size(), destination.logGroup());
                }
            }
        }

        /** The group and the stream are created when an event finds one of them missing, also after a delete. */
        private void putTo(Destination destination, List<Map<String, Object>> batch) {
            try {
                logs.putLogEventsForAccount(accountId, destination.logGroup(), destination.logStream(), batch,
                        destination.region());
            } catch (AwsException e) {
                if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                    throw e;
                }
                createLogGroup(destination);
                createLogStream(destination);
                logs.putLogEventsForAccount(accountId, destination.logGroup(), destination.logStream(), batch,
                        destination.region());
            }
        }

        private void createLogGroup(Destination destination) {
            try {
                logs.createLogGroupForAccount(accountId, destination.logGroup(), null, null, destination.region());
            } catch (AwsException e) {
                ignoreAlreadyExists(e);
            }
        }

        private void createLogStream(Destination destination) {
            try {
                logs.createLogStreamForAccount(accountId, destination.logGroup(), destination.logStream(),
                        destination.region());
            } catch (AwsException e) {
                ignoreAlreadyExists(e);
            }
        }

        private static void ignoreAlreadyExists(AwsException e) {
            if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }
}
