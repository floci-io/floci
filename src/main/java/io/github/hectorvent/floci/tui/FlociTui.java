package io.github.hectorvent.floci.tui;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;

import static io.github.hectorvent.floci.tui.TuiRenderer.*;

/**
 * Main TUI (Text User Interface) orchestrator for the Floci emulator.
 * Provides an interactive terminal dashboard for monitoring and controlling
 * all AWS services from the console.
 *
 * <p>Activation: set {@code floci.tui.enabled=true} or env {@code FLOCI_TUI_ENABLED=true}.
 * The TUI runs on a daemon thread alongside the HTTP server and does not
 * block the Quarkus event loop.
 */
@ApplicationScoped
public class FlociTui {

    private static final Logger LOG = Logger.getLogger(FlociTui.class);

    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final ResolvedServiceCatalog catalog;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread tuiThread;
    private Thread inputThread;

    // Views
    private TuiServiceView serviceView;
    private TuiConfigView configView;
    private TuiLogView logView;

    private volatile ActiveView activeView = ActiveView.DASHBOARD;
    private volatile boolean needsRedraw = true;

    // Terminal original state for restore on exit
    private String originalSttyState;

    enum ActiveView {
        DASHBOARD, SERVICES, CONFIG, LOGS
    }

    @Inject
    public FlociTui(EmulatorConfig config, ServiceRegistry serviceRegistry,
                    ResolvedServiceCatalog catalog) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.catalog = catalog;
    }

    public boolean isEnabled() {
        return config.tui().enabled();
    }

    /**
     * Returns a JUL Handler that captures log output for the log view.
     * Should be registered before the TUI starts.
     */
    public Optional<Handler> getLogHandler() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (logView == null) {
            logView = new TuiLogView();
        }
        return Optional.of(logView.createLogHandler());
    }

    /**
     * Starts the TUI on a daemon thread. Called after Quarkus is ready.
     */
    public void start() {
        if (!isEnabled() || running.getAndSet(true)) {
            return;
        }

        serviceView = new TuiServiceView(catalog);
        configView = new TuiConfigView(config);
        if (logView == null) {
            logView = new TuiLogView();
        }

        LOG.info("Starting TUI console...");

        // Save terminal state and switch to raw mode
        saveTerminalState();
        enableRawMode();
        hideCursor();

        // Input reading thread
        inputThread = new Thread(this::inputLoop, "floci-tui-input");
        inputThread.setDaemon(true);
        inputThread.start();

        // Render loop thread
        tuiThread = new Thread(this::renderLoop, "floci-tui-render");
        tuiThread.setDaemon(true);
        tuiThread.start();
    }

    /**
     * Stops the TUI and restores the terminal.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (inputThread != null) {
            inputThread.interrupt();
        }
        if (tuiThread != null) {
            tuiThread.interrupt();
        }

        showCursor();
        restoreTerminalState();
        clearScreen();
        println(BOLD + FG_CYAN + "Floci TUI stopped." + RESET);
        flush();
    }

    private void renderLoop() {
        int refreshMs = config.tui().refreshIntervalSeconds() * 1000;

        while (running.get()) {
            try {
                if (needsRedraw) {
                    render();
                    needsRedraw = false;
                }
                Thread.sleep(refreshMs);
                needsRedraw = true; // periodic refresh
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void inputLoop() {
        InputStream in = System.in;
        while (running.get()) {
            try {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                handleInput(b, in);
                needsRedraw = true;
            } catch (IOException e) {
                if (running.get()) {
                    LOG.debugv("TUI input error: {0}", e.getMessage());
                }
                break;
            }
        }
    }

    private void handleInput(int b, InputStream in) throws IOException {
        switch (activeView) {
            case DASHBOARD -> handleDashboardInput(b);
            case SERVICES -> handleServiceInput(b, in);
            case CONFIG -> handleConfigInput(b);
            case LOGS -> handleLogInput(b, in);
        }
    }

    private void handleDashboardInput(int b) {
        switch (b) {
            case 'q', 'Q' -> {
                stop();
                System.exit(0);
            }
            case 's', 'S' -> activeView = ActiveView.SERVICES;
            case 'c', 'C' -> activeView = ActiveView.CONFIG;
            case 'l', 'L' -> activeView = ActiveView.LOGS;
            case 'd', 'D' -> activeView = ActiveView.DASHBOARD;
            default -> { /* ignore */ }
        }
    }

    private void handleServiceInput(int b, InputStream in) throws IOException {
        switch (b) {
            case 27 -> { // Escape or arrow key sequence
                if (in.available() > 0) {
                    int next = in.read();
                    if (next == '[') {
                        int arrow = in.read();
                        switch (arrow) {
                            case 'A' -> serviceView.prevPage(); // Up arrow
                            case 'B' -> serviceView.nextPage(); // Down arrow
                        }
                    }
                } else {
                    activeView = ActiveView.DASHBOARD;
                }
            }
            case 'f', 'F' -> serviceView.cycleFilter();
            case 'q', 'Q' -> activeView = ActiveView.DASHBOARD;
            default -> { /* ignore */ }
        }
    }

    private void handleConfigInput(int b) {
        if (b == 27 || b == 'q' || b == 'Q') {
            activeView = ActiveView.DASHBOARD;
        }
    }

    private void handleLogInput(int b, InputStream in) throws IOException {
        switch (b) {
            case 27 -> { // Escape or arrow key
                if (in.available() > 0) {
                    int next = in.read();
                    if (next == '[') {
                        int arrow = in.read();
                        switch (arrow) {
                            case 'A' -> logView.scrollUp(3);  // Up
                            case 'B' -> logView.scrollDown(3, 18); // Down
                        }
                    }
                } else {
                    activeView = ActiveView.DASHBOARD;
                }
            }
            case 'a', 'A' -> logView.toggleAutoFollow();
            case 'q', 'Q' -> activeView = ActiveView.DASHBOARD;
            default -> { /* ignore */ }
        }
    }

    private void render() {
        int[] termSize = getTerminalSize();
        int rows = termSize[0];
        int cols = Math.max(60, termSize[1]);

        clearScreen();

        switch (activeView) {
            case DASHBOARD -> renderDashboard(cols);
            case SERVICES -> renderServices(cols);
            case CONFIG -> renderConfig(cols);
            case LOGS -> renderLogs(cols, rows);
        }

        flush();
    }

    private void renderDashboard(int width) {
        // Top border
        println(topBorder(width));

        // Title
        String version = resolveVersion();
        String title = BOLD + FG_BRIGHT_CYAN + "FLOCI AWS Emulator" + RESET
                + FG_GRAY + " — TUI Console" + RESET
                + "  " + FG_GRAY + "v" + version + RESET;
        println(boxRow(title, width));
        println(middleBorder(width));

        // Status overview
        String region = FG_WHITE + "Region: " + RESET + FG_BRIGHT_GREEN + config.defaultRegion() + RESET;
        String account = FG_WHITE + "Account: " + RESET + FG_BRIGHT_GREEN + config.defaultAccountId() + RESET;
        String port = FG_WHITE + "Port: " + RESET + FG_BRIGHT_GREEN + config.port() + RESET;
        println(boxRow("  " + region + "  " + account + "  " + port, width));

        String storage = FG_WHITE + "Storage: " + RESET + storageLabel(config.storage().mode());
        String tls = FG_WHITE + "TLS: " + RESET + (config.tls().enabled()
                ? FG_BRIGHT_GREEN + "enabled" + RESET
                : FG_GRAY + "disabled" + RESET);

        long enabledCount = catalog.allStatusDescriptors().stream()
                .filter(d -> d.enabled())
                .count();
        long totalCount = catalog.allStatusDescriptors().size();
        String services = FG_WHITE + "Services: " + RESET
                + FG_BRIGHT_GREEN + enabledCount + RESET + "/" + totalCount;

        println(boxRow("  " + storage + "  " + tls + "  " + services, width));
        println(boxRow("", width));

        // Navigation
        println(middleBorder(width));
        String nav = "  "
                + BOLD + FG_BRIGHT_CYAN + "[S]" + RESET + "ervices  "
                + BOLD + FG_BRIGHT_CYAN + "[C]" + RESET + "onfig  "
                + BOLD + FG_BRIGHT_CYAN + "[L]" + RESET + "ogs  "
                + BOLD + FG_RED + "[Q]" + RESET + "uit";
        println(boxRow(nav, width));
        println(middleBorder(width));

        // Quick service summary (top 20)
        println(boxRow(BOLD + FG_BRIGHT_CYAN + "  Service Overview" + RESET
                + FG_GRAY + " (press [S] for full list)" + RESET, width));
        println(middleBorder(width));

        // Column headers
        String header = BOLD + FG_WHITE
                + padRight("  SERVICE", 22)
                + padRight("STATUS", 12)
                + padRight("PROTOCOL", 14)
                + RESET;
        println(boxRow(header, width));

        var allServices = catalog.allStatusDescriptors();
        int displayCount = Math.min(20, allServices.size());
        for (int i = 0; i < displayCount; i++) {
            var svc = allServices.get(i);
            String indicator = statusIndicator(svc.enabled());
            String name = padRight(svc.externalKey(), 18);
            String status = padRight(statusLabel(svc.enabled()), 12);
            String protocol = padRight(protocolLabel(svc.defaultProtocol() != null ? svc.defaultProtocol().name() : null), 14);
            println(boxRow("  " + indicator + " " + name + status + protocol, width));
        }

        if (allServices.size() > displayCount) {
            println(boxRow(FG_GRAY + "  ... and " + (allServices.size() - displayCount) + " more services" + RESET, width));
        }

        // Bottom border
        println(bottomBorder(width));
    }

    private void renderServices(int width) {
        println(topBorder(width));
        String title = BOLD + FG_BRIGHT_CYAN + "FLOCI" + RESET + " — " + BOLD + "Services" + RESET;
        println(boxRow(title, width));
        println(middleBorder(width));

        List<String> serviceLines = serviceView.render(width);
        for (String line : serviceLines) {
            println(line);
        }

        println(bottomBorder(width));
    }

    private void renderConfig(int width) {
        println(topBorder(width));
        String title = BOLD + FG_BRIGHT_CYAN + "FLOCI" + RESET + " — " + BOLD + "Configuration" + RESET;
        println(boxRow(title, width));
        println(middleBorder(width));

        List<String> configLines = configView.render(width);
        for (String line : configLines) {
            println(line);
        }

        println(bottomBorder(width));
    }

    private void renderLogs(int width, int rows) {
        println(topBorder(width));
        String title = BOLD + FG_BRIGHT_CYAN + "FLOCI" + RESET + " — " + BOLD + "Logs" + RESET;
        println(boxRow(title, width));
        println(middleBorder(width));

        int maxLogLines = Math.max(10, rows - 6);
        List<String> logLines = logView.render(width, maxLogLines);
        for (String line : logLines) {
            println(line);
        }

        println(bottomBorder(width));
    }

    private void saveTerminalState() {
        try {
            ProcessBuilder pb = new ProcessBuilder("stty", "-g")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            originalSttyState = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            originalSttyState = null;
        }
    }

    private void enableRawMode() {
        try {
            new ProcessBuilder("stty", "raw", "-echo")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException e) {
            LOG.warnv("Failed to enable raw terminal mode: {0}", e.getMessage());
        }
    }

    private void restoreTerminalState() {
        try {
            if (originalSttyState != null) {
                new ProcessBuilder("stty", originalSttyState)
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start()
                        .waitFor();
            } else {
                new ProcessBuilder("stty", "sane")
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start()
                        .waitFor();
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnv("Failed to restore terminal state: {0}", e.getMessage());
        }
    }

    private static String resolveVersion() {
        String env = System.getenv("FLOCI_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }
}
