package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.UsageLine;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Emits a synthetic {@code Credit} {@code RECORD_TYPE} line per calendar month
 * covered by the request, sized at {@code -min(creditUsd, monthly Usage)} so net
 * cost never goes below zero.
 * <p>
 * Configured by {@code FLOCI_SERVICES_CE_CREDIT_USD_MONTHLY}. When zero, no
 * credit lines are emitted.
 */
final class CreditLineEmitter {

    private final double monthlyCreditUsd;

    CreditLineEmitter(double monthlyCreditUsd) {
        this.monthlyCreditUsd = monthlyCreditUsd;
    }

    /**
     * Returns a stream of credit lines, one per (region, month) pair where
     * {@code lines} contains usage in that region and month.
     * Each credit line carries a quantity equal to {@code -monthlyCreditUsd}
     * capped at the monthly usage cost, with {@code USD} as the unit.
     * <p>
     * Each input usage line may span an arbitrary window — enumerators emit one
     * line for the whole request period — so the line's cost is split across
     * every calendar month its window touches, weighted by the second-overlap
     * with each month, before per-month credits are computed.
     */
    Stream<UsageLine> emit(Stream<UsageLine> lines, CostSynthesizer synthesizer) {
        if (monthlyCreditUsd <= 0) {
            return Stream.empty();
        }
        Map<String, Double> usageCostByKey = new HashMap<>();
        Map<String, Instant[]> windowByKey = new HashMap<>();
        Map<String, String> regionByKey = new HashMap<>();
        Map<String, String> accountByKey = new HashMap<>();
        lines.forEach(line -> {
            if (!UsageLine.RECORD_TYPE_USAGE.equals(line.recordType())) {
                return;
            }
            double totalCost = synthesizer.compute(line).get("UnblendedCost").amount();
            if (totalCost == 0.0) {
                return;
            }
            long lineSeconds = Math.max(1, line.periodEnd().getEpochSecond() - line.periodStart().getEpochSecond());

            YearMonth ym = YearMonth.from(line.periodStart().atOffset(ZoneOffset.UTC));
            Instant cursor = line.periodStart();
            while (cursor.isBefore(line.periodEnd())) {
                Instant monthStart = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant overlapStart = cursor.isAfter(monthStart) ? cursor : monthStart;
                Instant overlapEnd = line.periodEnd().isBefore(monthEnd) ? line.periodEnd() : monthEnd;
                long overlapSeconds = Math.max(0, overlapEnd.getEpochSecond() - overlapStart.getEpochSecond());
                if (overlapSeconds > 0) {
                    double monthCost = totalCost * (overlapSeconds / (double) lineSeconds);
                    String key = line.region() + "|" + ym;
                    usageCostByKey.merge(key, monthCost, Double::sum);
                    YearMonth captured = ym;
                    windowByKey.computeIfAbsent(key, k -> new Instant[] {
                            captured.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                            captured.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant() });
                    regionByKey.put(key, line.region());
                    accountByKey.put(key, line.linkedAccountId());
                }
                cursor = monthEnd;
                ym = ym.plusMonths(1);
            }
        });

        List<UsageLine> credits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : usageCostByKey.entrySet()) {
            String key = entry.getKey();
            double appliedCredit = Math.min(monthlyCreditUsd, entry.getValue());
            if (appliedCredit <= 0) {
                continue;
            }
            Instant[] window = windowByKey.get(key);
            credits.add(new UsageLine(
                    window[0], window[1],
                    "Credits", regionByKey.get(key),
                    "Credit-Promotional", "ApplyPromoCredit",
                    UsageLine.RECORD_TYPE_CREDIT,
                    accountByKey.get(key),
                    null,
                    Map.of(),
                    -appliedCredit,
                    "USD"));
        }
        return credits.stream();
    }
}
