package io.github.hectorvent.floci.services.floci.ui;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads and caches the embedded HTML page served to browsers. One file holds two
 * views, the dashboard and the "starting the UI" wait view, and picks between them
 * from the request path. It is read from the classpath ({@code ui/*.html}) and must
 * be registered in {@code quarkus.native.resources.includes} so it is embedded in
 * the native image.
 */
@ApplicationScoped
public class UiPages {

    private static final String LANDING_RESOURCE = "ui/index.html";

    private volatile String landing;

    public String landingHtml() {
        String result = landing;
        if (result == null) {
            result = readResource(LANDING_RESOURCE);
            landing = result;
        }
        return result;
    }

    private static String readResource(String resourceName) {
        ClassLoader loader = UiPages.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing UI resource: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load UI resource: " + resourceName, e);
        }
    }
}
