package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The daily backup window and weekly maintenance window the RDS family of services carries, with
 * the checks a live account applies to them and its wording: times in {@code hh24:mi}, days in
 * {@code ddd:hh24:mi}, at least 30 minutes each, and the two may not overlap.
 */
public final class BackupWindows {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
    private static final int MINIMUM_WINDOW_MINUTES = 30;

    /** Where AWS picks a random 30-minute window, Floci picks these. */
    public static final String DEFAULT_BACKUP_WINDOW = "04:00-06:00";
    public static final String DEFAULT_MAINTENANCE_WINDOW = "mon:00:00-mon:03:00";

    private BackupWindows() {
    }

    /** Validates a daily window and returns its start and end as minutes of the day. */
    public static int[] parseBackupWindow(String window) {
        String[] parts = window.split("-", -1);
        if (parts.length != 2) {
            throw invalidBackupTime(window);
        }
        int start = minuteOfDay(parts[0], () -> invalidBackupTime(parts[0]));
        int end = minuteOfDay(parts[1], () -> invalidBackupTime(parts[1]));
        if (end <= start) {
            end += MINUTES_PER_DAY;
        }
        if (end - start < MINIMUM_WINDOW_MINUTES) {
            throw new AwsException("InvalidParameterValue",
                    "Backup window must be at least " + MINIMUM_WINDOW_MINUTES + " minutes.", 400);
        }
        return new int[] {start, end};
    }

    /** Validates a weekly window and returns its start and end as minutes of the week. */
    public static int[] parseMaintenanceWindow(String window) {
        String[] parts = window.split("-", -1);
        if (parts.length != 2) {
            throw invalidMaintenanceTime(window);
        }
        int start = minuteOfWeek(parts[0]);
        int end = minuteOfWeek(parts[1]);
        if (end <= start) {
            end += MINUTES_PER_WEEK;
        }
        if (end - start < MINIMUM_WINDOW_MINUTES) {
            throw new AwsException("InvalidParameterValue",
                    "Maintenance window must be at least " + MINIMUM_WINDOW_MINUTES + " minutes.", 400);
        }
        if (end - start >= MINUTES_PER_DAY) {
            throw new AwsException("InvalidParameterValue",
                    "Maintenance window must be less than 24 hours.", 400);
        }
        return new int[] {start, end};
    }

    public static boolean overlap(String backupWindow, String maintenanceWindow) {
        int[] backup = parseBackupWindow(backupWindow);
        int[] maintenance = parseMaintenanceWindow(maintenanceWindow);
        // the backup window recurs daily: lay it over each day and compare with the maintenance
        // window in this week and the neighbouring ones, since either may wrap the week boundary
        for (int day = 0; day < 7; day++) {
            int start = day * MINUTES_PER_DAY + backup[0];
            int end = day * MINUTES_PER_DAY + backup[1];
            for (int shift : new int[] {-MINUTES_PER_WEEK, 0, MINUTES_PER_WEEK}) {
                if (start < maintenance[1] - shift && maintenance[0] - shift < end) {
                    return true;
                }
            }
        }
        return false;
    }

    public static AwsException overlapping() {
        return new AwsException("InvalidParameterValue",
                "The backup window and maintenance window must not overlap.", 400);
    }

    /** A 30-minute maintenance window starting the minute the given daily window ends. */
    public static String maintenanceWindowAfter(String backupWindow) {
        int end = parseBackupWindow(backupWindow)[1] % MINUTES_PER_DAY;
        return DAYS.get(0) + ":" + time(end) + "-" + DAYS.get((end + MINIMUM_WINDOW_MINUTES) / MINUTES_PER_DAY)
                + ":" + time(end + MINIMUM_WINDOW_MINUTES);
    }

    /** A 30-minute daily backup window starting the minute the given weekly window ends. */
    public static String backupWindowAfter(String maintenanceWindow) {
        int end = parseMaintenanceWindow(maintenanceWindow)[1] % MINUTES_PER_DAY;
        return time(end) + "-" + time(end + MINIMUM_WINDOW_MINUTES);
    }

    public static String lowerCase(String window) {
        return window == null ? null : window.toLowerCase();
    }

    private static int minuteOfWeek(String dayAndTime) {
        int colon = dayAndTime.indexOf(':');
        int day = colon < 0 ? -1 : DAYS.indexOf(dayAndTime.substring(0, colon).toLowerCase());
        if (day < 0) {
            throw invalidMaintenanceTime(dayAndTime);
        }
        return day * MINUTES_PER_DAY
                + minuteOfDay(dayAndTime.substring(colon + 1), () -> invalidMaintenanceTime(dayAndTime));
    }

    private static int minuteOfDay(String time, java.util.function.Supplier<AwsException> invalid) {
        Matcher m = TIME.matcher(time);
        if (!m.matches()) {
            throw invalid.get();
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    private static String time(int minuteOfDay) {
        int m = minuteOfDay % MINUTES_PER_DAY;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static AwsException invalidBackupTime(String time) {
        return new AwsException("InvalidParameterValue", "Invalid backup window time '" + time
                + "' specified. Should be specified as a time hh24:mi (24H Clock UTC). Example: 03:15", 400);
    }

    private static AwsException invalidMaintenanceTime(String time) {
        return new AwsException("InvalidParameterValue", "Invalid maintenance window time '" + time
                + "' specified. Should be specified as a time ddd:hh24:mi (24H Clock UTC). Example: Mon:00:15", 400);
    }
}
