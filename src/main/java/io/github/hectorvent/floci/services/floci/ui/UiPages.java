package io.github.hectorvent.floci.services.floci.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Loads and caches the small embedded HTML pages served to browsers: the Floci
 * landing page and the "starting the UI" interstitial. Resources are read from
 * the classpath ({@code ui/*.html}) and must be registered in
 * {@code quarkus.native.resources.includes} so they are embedded in the native
 * image.
 */
@ApplicationScoped
public class UiPages {

    private static final String LANDING_RESOURCE = "ui/index.html";
    private static final String STARTING_RESOURCE = "ui/starting.html";

    private volatile String landing;
    private volatile String starting;

    public String landingHtml() {
        String result = landing;
        if (result == null) {
            result = readResource(LANDING_RESOURCE);
            landing = result;
        }
        return result;
    }

    public String startingHtml() {
        String result = starting;
        if (result == null) {
            result = readResource(STARTING_RESOURCE);
            starting = result;
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
