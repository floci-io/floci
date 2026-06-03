package io.github.hectorvent.floci.services.ec2;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.github.hectorvent.floci.services.ec2.model.Image;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Ec2ImageCatalog {

    private static final String CATALOG_RESOURCE_NAME = "ec2/image-catalog.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final String defaultDockerImage;
    private final List<CatalogImage> images;
    private final Map<String, CatalogImage> imagesByIdOrAlias;

    public Ec2ImageCatalog() {
        this(readResource(CATALOG_RESOURCE_NAME, Catalog.class));
    }

    Ec2ImageCatalog(Catalog catalog) {
        this.defaultDockerImage = require(catalog.defaultDockerImage, "defaultDockerImage");
        this.images = List.copyOf(catalog.images == null ? List.of() : catalog.images);
        if (this.images.isEmpty()) {
            throw new IllegalStateException("EC2 image catalog has no images: " + CATALOG_RESOURCE_NAME);
        }
        this.imagesByIdOrAlias = indexImages(this.images);
    }

    public String defaultDockerImage() {
        return defaultDockerImage;
    }

    public List<CatalogImage> images() {
        return images;
    }

    public Optional<CatalogImage> findByIdOrAlias(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(imagesByIdOrAlias.get(imageId));
    }

    private static <T> T readResource(String resourceName, Class<T> type) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing EC2 image catalog resource: " + resourceName);
            }
            return YAML_MAPPER.readValue(input, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load EC2 image catalog resource: " + resourceName, e);
        }
    }

    private static Map<String, CatalogImage> indexImages(List<CatalogImage> images) {
        Map<String, CatalogImage> index = new LinkedHashMap<>();
        for (CatalogImage image : images) {
            addIndexEntry(index, require(image.imageId, "imageId"), image);
            require(image.dockerImage, "dockerImage");
            require(image.name, "name");
            require(image.description, "description");
            require(image.architecture, "architecture");
            require(image.creationDate, "creationDate");

            for (String alias : image.aliases()) {
                addIndexEntry(index, alias, image);
            }
        }
        return Map.copyOf(index);
    }

    private static void addIndexEntry(Map<String, CatalogImage> index, String imageIdOrAlias, CatalogImage image) {
        CatalogImage previous = index.putIfAbsent(imageIdOrAlias, image);
        if (previous != null) {
            throw new IllegalStateException("Duplicate EC2 image catalog id or alias: " + imageIdOrAlias);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("EC2 image catalog entry is missing required field: " + field);
        }
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static final class Catalog {
        public String defaultDockerImage;
        public List<CatalogImage> images = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static final class CatalogImage {
        public String imageId;
        public List<String> aliases = List.of();
        public String dockerImage;
        public String name;
        public String description;
        public String state;
        public String ownerId;
        public Boolean publicImage;
        public String architecture;
        public String rootDeviceType;
        public String rootDeviceName;
        public String virtualizationType;
        public String hypervisor;
        public String platform;
        public String imageOwnerAlias;
        public String creationDate;
        public Boolean advertised;

        public boolean advertised() {
            return advertised == null || advertised;
        }

        public List<String> aliases() {
            return aliases == null ? List.of() : List.copyOf(aliases);
        }

        public List<String> idsAndAliases() {
            List<String> ids = new ArrayList<>();
            ids.add(imageId);
            ids.addAll(aliases());
            return ids;
        }

        public boolean matchesIdOrAlias(List<String> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return true;
            }
            for (String id : idsAndAliases()) {
                if (candidates.contains(id)) {
                    return true;
                }
            }
            return false;
        }

        public boolean matchesOwner(List<String> owners) {
            if (owners == null || owners.isEmpty()) {
                return true;
            }
            Image image = toImage();
            return owners.contains(image.getOwnerId()) || owners.contains(image.getImageOwnerAlias());
        }

        public Image toImage() {
            Image image = new Image();
            image.setImageId(imageId);
            image.setName(name);
            image.setDescription(description);
            if (state != null) {
                image.setState(state);
            }
            if (ownerId != null) {
                image.setOwnerId(ownerId);
            }
            if (publicImage != null) {
                image.setPublic(publicImage);
            }
            image.setArchitecture(architecture);
            if (rootDeviceType != null) {
                image.setRootDeviceType(rootDeviceType);
            }
            if (rootDeviceName != null) {
                image.setRootDeviceName(rootDeviceName);
            }
            if (virtualizationType != null) {
                image.setVirtualizationType(virtualizationType);
            }
            if (hypervisor != null) {
                image.setHypervisor(hypervisor);
            }
            image.setPlatform(platform);
            if (imageOwnerAlias != null) {
                image.setImageOwnerAlias(imageOwnerAlias);
            }
            image.setCreationDate(creationDate);
            return image;
        }
    }
}
