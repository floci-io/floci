package io.github.hectorvent.floci.tui;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;

import java.util.List;

import static io.github.hectorvent.floci.tui.TuiRenderer.*;

/**
 * Renders the service dashboard view for the TUI console.
 * Shows a paginated table of all AWS services with their status,
 * protocol, and storage mode.
 */
final class TuiServiceView {

    private static final int ROWS_PER_PAGE = 15;

    private final ResolvedServiceCatalog catalog;
    private int currentPage;
    private int totalPages;
    private ServiceFilter filter = ServiceFilter.ALL;

    enum ServiceFilter {
        ALL, RUNNING, DISABLED
    }

    TuiServiceView(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
        this.currentPage = 0;
        recalculatePages();
    }

    void nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
        }
    }

    void prevPage() {
        if (currentPage > 0) {
            currentPage--;
        }
    }

    void cycleFilter() {
        filter = switch (filter) {
            case ALL -> ServiceFilter.RUNNING;
            case RUNNING -> ServiceFilter.DISABLED;
            case DISABLED -> ServiceFilter.ALL;
        };
        currentPage = 0;
        recalculatePages();
    }

    private List<ServiceDescriptor> getFilteredServices() {
        List<ServiceDescriptor> all = catalog.allStatusDescriptors();
        return switch (filter) {
            case ALL -> all;
            case RUNNING -> all.stream().filter(ServiceDescriptor::enabled).toList();
            case DISABLED -> all.stream().filter(d -> !d.enabled()).toList();
        };
    }

    private void recalculatePages() {
        int total = getFilteredServices().size();
        totalPages = Math.max(1, (total + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }
    }

    /**
     * Renders the service table to a list of strings, one per line.
     */
    List<String> render(int width) {
        List<String> lines = new java.util.ArrayList<>();
        List<ServiceDescriptor> services = getFilteredServices();
        recalculatePages();

        int innerWidth = width - 4;

        // Filter bar
        String filterLabel = switch (filter) {
            case ALL -> BOLD + FG_WHITE + "ALL" + RESET;
            case RUNNING -> BOLD + FG_BRIGHT_GREEN + "RUNNING" + RESET;
            case DISABLED -> BOLD + FG_RED + "DISABLED" + RESET;
        };
        String counts = FG_GRAY + "(" + services.size() + " services)" + RESET;
        lines.add(boxRow("  Filter: " + filterLabel + "  " + counts + "  " + FG_GRAY + "[F] cycle filter" + RESET, width));
        lines.add(middleBorder(width));

        // Header
        String header = BOLD + FG_BRIGHT_CYAN
                + padRight("  SERVICE", 22)
                + padRight("STATUS", 12)
                + padRight("PROTOCOL", 14)
                + padRight("STORAGE", 12)
                + RESET;
        lines.add(boxRow(header, width));
        lines.add(middleBorder(width));

        // Service rows
        int start = currentPage * ROWS_PER_PAGE;
        int end = Math.min(start + ROWS_PER_PAGE, services.size());

        for (int i = start; i < end; i++) {
            ServiceDescriptor svc = services.get(i);
            String indicator = statusIndicator(svc.enabled());
            String name = padRight(svc.externalKey(), 18);
            String status = padRight(statusLabel(svc.enabled()), 12);
            String protocol = padRight(protocolLabel(svc.defaultProtocol() != null ? svc.defaultProtocol().name() : null), 14);
            String storage = padRight(storageLabel(svc.storageMode()), 12);

            String row = "  " + indicator + " " + name + status + protocol + storage;
            lines.add(boxRow(row, width));
        }

        // Fill remaining rows for consistent layout
        for (int i = end - start; i < ROWS_PER_PAGE; i++) {
            lines.add(boxRow("", width));
        }

        // Footer with pagination
        lines.add(middleBorder(width));
        String pageInfo = FG_GRAY + "  Page " + (currentPage + 1) + "/" + totalPages
                + "  [↑/↓] Navigate  [F] Filter  [Enter] Details  [Esc] Back" + RESET;
        lines.add(boxRow(pageInfo, width));

        return lines;
    }
}
