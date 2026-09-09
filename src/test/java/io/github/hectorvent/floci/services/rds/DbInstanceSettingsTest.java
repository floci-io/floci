package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.services.rds.model.DbInstanceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbInstanceSettingsTest {

    private static String time(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    @Test
    void derivedMaintenanceWindowIsClearOfAnyBackupWindowThatLeavesRoomForOne() {
        // every start minute and every length up to 23h30 — the longest daily window that still
        // leaves a 30-minute gap; beyond that no clear counterpart exists at all
        for (int start = 0; start < 1440; start += 5) {
            for (int length = 30; length <= 1410; length += 5) {
                String backup = time(start) + "-" + time(start + length);
                String maintenance = DbInstanceSettings.maintenanceWindowAfter(backup);
                assertFalse(DbInstanceSettings.windowsOverlap(backup, maintenance),
                        backup + " vs derived " + maintenance);
            }
        }
        // 23h45 leaves a 15-minute gap: the derived window necessarily overlaps
        assertTrue(DbInstanceSettings.windowsOverlap("00:00-23:45",
                DbInstanceSettings.maintenanceWindowAfter("00:00-23:45")));
    }

    @Test
    void derivedBackupWindowIsClearOfAnyMaintenanceWindowThatLeavesRoomForOne() {
        String[] days = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        for (int start = 0; start < 7 * 1440; start += 37) {
            for (int length = 30; length <= 1410; length += 25) {
                int end = (start + length) % (7 * 1440);
                String maintenance = days[start / 1440] + ":" + time(start) + "-"
                        + days[end / 1440] + ":" + time(end);
                String backup = DbInstanceSettings.backupWindowAfter(maintenance);
                assertFalse(DbInstanceSettings.windowsOverlap(backup, maintenance),
                        maintenance + " vs derived " + backup);
            }
        }
        assertTrue(DbInstanceSettings.windowsOverlap(
                DbInstanceSettings.backupWindowAfter("mon:00:00-mon:23:45"), "mon:00:00-mon:23:45"));
    }
}
