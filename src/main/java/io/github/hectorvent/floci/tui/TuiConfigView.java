package io.github.hectorvent.floci.tui;

import io.github.hectorvent.floci.config.EmulatorConfig;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.tui.TuiRenderer.*;

/**
 * Renders the configuration overview view for the TUI console.
 * Shows the current emulator configuration in a readable format.
 */
final class TuiConfigView {

    private final EmulatorConfig config;

    TuiConfigView(EmulatorConfig config) {
        this.config = config;
    }

    List<String> render(int width) {
        List<String> lines = new ArrayList<>();

        // Section: General
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  General Configuration" + RESET, width));
        lines.add(middleBorder(width));
        lines.add(boxRow(configLine("Port", String.valueOf(config.port())), width));
        lines.add(boxRow(configLine("Base URL", config.effectiveBaseUrl()), width));
        lines.add(boxRow(configLine("Region", config.defaultRegion()), width));
        lines.add(boxRow(configLine("Account ID", config.defaultAccountId()), width));
        lines.add(boxRow(configLine("Max Request Size", config.maxRequestSize() + " MB"), width));
        lines.add(boxRow("", width));

        // Section: Storage
        lines.add(middleBorder(width));
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  Storage Configuration" + RESET, width));
        lines.add(middleBorder(width));
        lines.add(boxRow(configLine("Mode", storageLabel(config.storage().mode())), width));
        lines.add(boxRow(configLine("Persistent Path", config.storage().persistentPath()), width));
        lines.add(boxRow(configLine("Host Path", config.storage().hostPersistentPath()), width));
        lines.add(boxRow(configLine("Prune on Delete", String.valueOf(config.storage().pruneVolumesOnDelete())), width));
        lines.add(boxRow(configLine("WAL Compaction", config.storage().wal().compactionIntervalMs() + " ms"), width));
        lines.add(boxRow("", width));

        // Section: TLS
        lines.add(middleBorder(width));
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  TLS Configuration" + RESET, width));
        lines.add(middleBorder(width));
        String tlsStatus = config.tls().enabled()
                ? FG_BRIGHT_GREEN + "enabled" + RESET
                : FG_GRAY + "disabled" + RESET;
        lines.add(boxRow(configLine("TLS", tlsStatus), width));
        lines.add(boxRow(configLine("Self-Signed", String.valueOf(config.tls().selfSigned())), width));
        lines.add(boxRow("", width));

        // Section: Auth
        lines.add(middleBorder(width));
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  Auth Configuration" + RESET, width));
        lines.add(middleBorder(width));
        lines.add(boxRow(configLine("Validate Signatures", String.valueOf(config.auth().validateSignatures())), width));
        lines.add(boxRow("", width));

        // Section: Docker
        lines.add(middleBorder(width));
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  Docker Configuration" + RESET, width));
        lines.add(middleBorder(width));
        lines.add(boxRow(configLine("Docker Host", config.docker().dockerHost()), width));
        lines.add(boxRow(configLine("Log Max Size", config.docker().logMaxSize()), width));
        lines.add(boxRow(configLine("Log Max Files", config.docker().logMaxFile()), width));
        lines.add(boxRow("", width));

        // Footer
        lines.add(middleBorder(width));
        lines.add(boxRow(FG_GRAY + "  [Esc] Back to dashboard" + RESET, width));

        return lines;
    }

    private static String configLine(String key, String value) {
        return "  " + padRight(FG_WHITE + key + RESET, 22) + " " + value;
    }
}
