package io.github.hectorvent.floci.tui;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Low-level ANSI terminal rendering utilities for the Floci TUI console.
 * Uses standard ANSI escape sequences for colors, cursor movement, and box drawing.
 * No external TUI libraries — keeps native-image compatibility simple.
 */
final class TuiRenderer {

    // ANSI escape sequences
    static final String ESC = "\033[";
    static final String RESET = ESC + "0m";
    static final String BOLD = ESC + "1m";
    static final String DIM = ESC + "2m";
    static final String ITALIC = ESC + "3m";
    static final String UNDERLINE = ESC + "4m";

    // Foreground colors
    static final String FG_BLACK = ESC + "30m";
    static final String FG_RED = ESC + "31m";
    static final String FG_GREEN = ESC + "32m";
    static final String FG_YELLOW = ESC + "33m";
    static final String FG_BLUE = ESC + "34m";
    static final String FG_MAGENTA = ESC + "35m";
    static final String FG_CYAN = ESC + "36m";
    static final String FG_WHITE = ESC + "37m";
    static final String FG_GRAY = ESC + "90m";
    static final String FG_BRIGHT_GREEN = ESC + "92m";
    static final String FG_BRIGHT_YELLOW = ESC + "93m";
    static final String FG_BRIGHT_CYAN = ESC + "96m";
    static final String FG_BRIGHT_WHITE = ESC + "97m";

    // Background colors
    static final String BG_BLACK = ESC + "40m";
    static final String BG_RED = ESC + "41m";
    static final String BG_GREEN = ESC + "42m";
    static final String BG_BLUE = ESC + "44m";
    static final String BG_MAGENTA = ESC + "45m";
    static final String BG_CYAN = ESC + "46m";
    static final String BG_GRAY = ESC + "100m";

    // Box drawing characters (Unicode)
    static final String BOX_TL = "┌";
    static final String BOX_TR = "┐";
    static final String BOX_BL = "└";
    static final String BOX_BR = "┘";
    static final String BOX_H = "─";
    static final String BOX_V = "│";
    static final String BOX_LT = "├";
    static final String BOX_RT = "┤";
    static final String BOX_TT = "┬";
    static final String BOX_BT = "┴";
    static final String BOX_CROSS = "┼";

    // Status indicators
    static final String INDICATOR_ON = "●";
    static final String INDICATOR_OFF = "○";
    static final String INDICATOR_WARN = "◆";

    private static final PrintStream out = System.out;

    private TuiRenderer() {
    }

    /**
     * Detects terminal dimensions using stty. Falls back to 80x24.
     */
    static int[] getTerminalSize() {
        try {
            ProcessBuilder pb = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            if (!output.isEmpty()) {
                String[] parts = output.split("\\s+");
                if (parts.length == 2) {
                    return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                }
            }
        } catch (IOException | InterruptedException | NumberFormatException ignored) {
            // fall through
        }
        return new int[]{24, 80};
    }

    static void clearScreen() {
        out.print(ESC + "2J" + ESC + "H");
        out.flush();
    }

    static void moveCursor(int row, int col) {
        out.printf("%s%d;%dH", ESC, row, col);
    }

    static void hideCursor() {
        out.print(ESC + "?25l");
        out.flush();
    }

    static void showCursor() {
        out.print(ESC + "?25h");
        out.flush();
    }

    /**
     * Draws a horizontal line spanning the given width, using box-drawing characters.
     */
    static String horizontalLine(int width, String left, String right) {
        return left + BOX_H.repeat(Math.max(0, width - 2)) + right;
    }

    static String topBorder(int width) {
        return FG_CYAN + horizontalLine(width, BOX_TL, BOX_TR) + RESET;
    }

    static String bottomBorder(int width) {
        return FG_CYAN + horizontalLine(width, BOX_BL, BOX_BR) + RESET;
    }

    static String middleBorder(int width) {
        return FG_CYAN + horizontalLine(width, BOX_LT, BOX_RT) + RESET;
    }

    /**
     * Pads or truncates content to fit inside a box row with borders on each side.
     */
    static String boxRow(String content, int width) {
        int visibleLen = stripAnsi(content).length();
        int padding = Math.max(0, width - 4 - visibleLen);
        return FG_CYAN + BOX_V + RESET + " " + content + " ".repeat(padding) + " " + FG_CYAN + BOX_V + RESET;
    }

    /**
     * Removes ANSI escape sequences to compute visible string length.
     */
    static String stripAnsi(String text) {
        return text.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
    }

    static int visibleLength(String text) {
        return stripAnsi(text).length();
    }

    /**
     * Right-pads a string (with ANSI awareness) to the given visible width.
     */
    static String padRight(String text, int width) {
        int visible = visibleLength(text);
        if (visible >= width) {
            return text;
        }
        return text + " ".repeat(width - visible);
    }

    /**
     * Centers text within the given width.
     */
    static String center(String text, int width) {
        int visible = visibleLength(text);
        if (visible >= width) {
            return text;
        }
        int leftPad = (width - visible) / 2;
        int rightPad = width - visible - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    /**
     * Colorize a service status indicator.
     */
    static String statusIndicator(boolean enabled) {
        if (enabled) {
            return FG_BRIGHT_GREEN + INDICATOR_ON + RESET;
        }
        return FG_RED + INDICATOR_OFF + RESET;
    }

    /**
     * Colorize a status label.
     */
    static String statusLabel(boolean enabled) {
        if (enabled) {
            return FG_BRIGHT_GREEN + "running" + RESET;
        }
        return FG_GRAY + "disabled" + RESET;
    }

    /**
     * Formats a protocol name with color.
     */
    static String protocolLabel(String protocol) {
        if (protocol == null) {
            return FG_GRAY + "—" + RESET;
        }
        return switch (protocol) {
            case "QUERY" -> FG_YELLOW + "Query" + RESET;
            case "JSON" -> FG_BRIGHT_CYAN + "JSON 1.1" + RESET;
            case "REST_JSON" -> FG_MAGENTA + "REST JSON" + RESET;
            case "REST_XML" -> FG_BLUE + "REST XML" + RESET;
            case "CBOR" -> FG_GREEN + "CBOR" + RESET;
            default -> FG_WHITE + protocol + RESET;
        };
    }

    /**
     * Formats a storage mode with color.
     */
    static String storageLabel(String mode) {
        if (mode == null || mode.isEmpty()) {
            return FG_GRAY + "—" + RESET;
        }
        return switch (mode) {
            case "memory" -> FG_GREEN + "memory" + RESET;
            case "persistent" -> FG_YELLOW + "persistent" + RESET;
            case "hybrid" -> FG_BRIGHT_CYAN + "hybrid" + RESET;
            case "wal" -> FG_MAGENTA + "wal" + RESET;
            default -> FG_WHITE + mode + RESET;
        };
    }

    static void print(String text) {
        out.print(text);
    }

    static void println(String text) {
        out.println(text);
    }

    static void println() {
        out.println();
    }

    static void flush() {
        out.flush();
    }
}
