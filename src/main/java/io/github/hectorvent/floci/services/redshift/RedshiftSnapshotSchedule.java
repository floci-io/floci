package io.github.hectorvent.floci.services.redshift;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import io.github.hectorvent.floci.core.common.AwsCronDefinitions;
import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redshift's UTC snapshot rules, including its minute/hour/day-of-week shorthand. */
final class RedshiftSnapshotSchedule {
    private static final Pattern RATE = Pattern.compile("rate\\(\\s*(\\d+)\\s+(hours?|days?)\\s*\\)");
    private static final Duration MINIMUM_INTERVAL = Duration.ofHours(1);

    private RedshiftSnapshotSchedule() { }

    static void validate(List<String> definitions) {
        try {
            if (definitions == null || definitions.isEmpty()) {
                throw new IllegalArgumentException("ScheduleDefinitions must contain at least one definition");
            }
            Instant anchor = Instant.now().truncatedTo(ChronoUnit.DAYS);
            List<Rule> rules = definitions.stream().map(RedshiftSnapshotSchedule::parse).toList();
            List<Instant> next = new ArrayList<>();
            for (Rule rule : rules) {
                next.add(rule.next(anchor, anchor).orElse(null));
            }
            Instant previous = null;
            Instant limit = null;
            while (true) {
                Instant earliest = next.stream().filter(value -> value != null).min(Instant::compareTo).orElse(null);
                if (earliest == null || limit != null && earliest.isAfter(limit)) {
                    break;
                }
                if (previous != null && Duration.between(previous, earliest).compareTo(MINIMUM_INTERVAL) < 0) {
                    throw new IllegalArgumentException("Snapshot schedules must be at least one hour apart");
                }
                if (limit == null) {
                    limit = earliest.plus(Duration.ofDays(400));
                }
                previous = earliest;
                for (int i = 0; i < rules.size(); i++) {
                    if (earliest.equals(next.get(i))) {
                        next.set(i, rules.get(i).next(anchor, earliest).orElse(null));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidSchedule", "Invalid snapshot schedule: " + e.getMessage(), 400);
        }
    }

    static Optional<Instant> latestDue(List<String> definitions, Instant anchor, Instant now) {
        return definitions.stream().map(RedshiftSnapshotSchedule::parse)
                .map(rule -> rule.latest(anchor, now)).flatMap(Optional::stream)
                .filter(time -> time.isAfter(anchor) && !time.isAfter(now)).max(Instant::compareTo);
    }

    private static Rule parse(String definition) {
        if (definition == null) {
            throw new IllegalArgumentException("A schedule definition cannot be null");
        }
        Matcher rate = RATE.matcher(definition);
        if (rate.matches()) {
            long value = Long.parseLong(rate.group(1));
            boolean days = rate.group(2).startsWith("day");
            if (value < 1 || days && value != 1 || !days && value > 24) {
                throw new IllegalArgumentException("Snapshot rates must be between one hour and one day");
            }
            long hours = days ? 24 : value;
            return new Rule(Duration.ofHours(hours), null);
        }
        if (!definition.startsWith("cron(") || !definition.endsWith(")")) {
            throw new IllegalArgumentException("Expected rate(...) or cron(...)");
        }
        String[] fields = definition.substring(5, definition.length() - 1).trim().split("\\s+");
        String normalized = switch (fields.length) {
            case 3 -> fields[0] + " " + fields[1] + " ? * " + fields[2] + " *";
            case 6 -> String.join(" ", fields);
            default -> throw new IllegalArgumentException("Redshift cron needs three or six fields");
        };
        String[] full = normalized.split(" ");
        if ("?".equals(full[2]) == "?".equals(full[4])) {
            throw new IllegalArgumentException("Use ? in exactly one of day-of-month and day-of-week");
        }
        Cron cron = AwsCronDefinitions.newParser().parse("0 " + normalized);
        cron.validate();
        return new Rule(null, ExecutionTime.forCron(cron));
    }

    private record Rule(Duration rate, ExecutionTime cron) {
        Optional<Instant> latest(Instant anchor, Instant now) {
            if (rate != null) {
                long count = Duration.between(anchor, now).getSeconds() / rate.getSeconds();
                return count < 1 ? Optional.empty() : Optional.of(anchor.plus(rate.multipliedBy(count)));
            }
            return cron.lastExecution(now.atZone(ZoneOffset.UTC).plusNanos(1)).map(value -> value.toInstant());
        }

        Optional<Instant> next(Instant anchor, Instant after) {
            if (rate != null) {
                long count = Math.max(0, Duration.between(anchor, after).getSeconds() / rate.getSeconds()) + 1;
                return Optional.of(anchor.plus(rate.multipliedBy(count)));
            }
            return cron.nextExecution(after.atZone(ZoneOffset.UTC)).map(value -> value.toInstant());
        }
    }
}
