package io.github.hectorvent.floci.tools.ami;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class AmiImageTool {
    public static final Path DEFAULT_METADATA = Path.of("docker/ec2/ami-images/image-build-metadata.yaml");
    public static final Path DEFAULT_OUTPUT = Path.of("target/ami-images");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private AmiImageTool() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Metadata metadata = loadMetadata(options.metadata);
        List<ImageSpec> images = options.imageId == null ? metadata.images : List.of(findImage(metadata, options.imageId));
        for (ImageSpec image : images) {
            switch (options.command) {
                case "plan" -> System.out.println(plan(image));
                case "resolve" -> resolve(image, options.output, true);
                case "generate" -> generate(image, options.output, true);
                case "update-catalog" -> updateCatalog(image, options.catalog, options.output, true);
                case "build" -> build(image, options.output);
                case "smoke" -> smoke(image);
                default -> throw new IllegalArgumentException("Unknown command: " + options.command);
            }
        }
    }

    public static Metadata loadMetadata(Path path) {
        try {
            Metadata metadata = YAML.readValue(path.toFile(), Metadata.class);
            validate(metadata);
            return metadata;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read AMI image metadata: " + path, e);
        }
    }

    public static String plan(ImageSpec image) {
        validateImage(image);
        return image.id + " -> " + image.docker.image + " from " + image.canonical.rootfsUrl();
    }

    public static Provenance resolve(ImageSpec image, Path outputRoot, boolean write) {
        validateImage(image);
        Path context = contextDir(outputRoot, image);
        try {
            Files.createDirectories(context);
            Path manifest = download(image.canonical.manifestUrl(), context.resolve(image.canonical.manifest));
            Provenance provenance = new Provenance();
            provenance.imageId = image.id;
            provenance.catalogImageId = image.catalogImageId;
            provenance.dockerImage = image.docker.image;
            provenance.family = image.family;
            provenance.version = image.version;
            provenance.architecture = image.architecture;
            provenance.aws = image.aws;
            provenance.rootfsUrl = image.canonical.rootfsUrl();
            provenance.rootfsSha256 = image.canonical.rootfsSha256;
            provenance.manifestUrl = image.canonical.manifestUrl();
            provenance.manifestSha256 = sha256(manifest);
            provenance.generatedAt = Instant.now().toString();
            if (write) {
                YAML.writerWithDefaultPrettyPrinter().writeValue(context.resolve("provenance.yaml").toFile(), provenance);
            }
            return provenance;
        } catch (IOException e) {
            throw new IllegalStateException("Could not resolve AMI image metadata for " + image.id, e);
        }
    }

    public static Path generate(ImageSpec image, Path outputRoot, boolean write) {
        Provenance provenance = resolve(image, outputRoot, write);
        Path context = contextDir(outputRoot, image);
        try {
            Path rootfs = download(image.canonical.rootfsUrl(), context.resolve(image.canonical.rootfs));
            String actual = sha256(rootfs);
            if (!image.canonical.rootfsSha256.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("Rootfs checksum mismatch for " + image.id
                        + ": expected " + image.canonical.rootfsSha256 + " but got " + actual);
            }
            if (write) {
                Files.writeString(context.resolve("Dockerfile"), dockerfile(image), StandardCharsets.UTF_8);
                Files.writeString(context.resolve("README.md"), readme(image), StandardCharsets.UTF_8);
                YAML.writerWithDefaultPrettyPrinter().writeValue(context.resolve("provenance.yaml").toFile(), provenance);
            }
            return context;
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate AMI image context for " + image.id, e);
        }
    }

    public static void updateCatalog(ImageSpec image, Path catalogPath, Path outputRoot, boolean write) {
        validateImage(image);
        try {
            Catalog catalog = YAML.readValue(catalogPath.toFile(), Catalog.class);
            CatalogImage staged = catalog.images.stream()
                    .filter(entry -> image.catalogImageId.equals(entry.imageId))
                    .findFirst()
                    .orElseGet(() -> {
                        CatalogImage entry = new CatalogImage();
                        catalog.images.add(entry);
                        return entry;
                    });
            staged.imageId = image.catalogImageId;
            staged.aliases = image.catalogAliases == null ? List.of() : List.copyOf(image.catalogAliases);
            staged.dockerImage = image.docker.image;
            staged.name = image.aws.name;
            staged.description = "Canonical, Ubuntu, " + image.version + " LTS cloud image";
            staged.ownerId = image.aws.ownerId;
            staged.imageOwnerAlias = "canonical";
            staged.architecture = awsArchitecture(image.architecture);
            staged.creationDate = image.aws.creationDate;
            staged.rootDeviceType = image.aws.rootDeviceType;
            staged.virtualizationType = image.aws.virtualizationType;
            staged.guestRuntime = image.guest.runtime;
            staged.cloudInit = image.guest.cloudInit;
            staged.provenance = contextDir(outputRoot, image).resolve("provenance.yaml").toString();
            if (write) {
                YAML.writerWithDefaultPrettyPrinter().writeValue(catalogPath.toFile(), catalog);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not update EC2 image catalog: " + catalogPath, e);
        }
    }

    public static void build(ImageSpec image, Path outputRoot) {
        Path context = generate(image, outputRoot, true);
        run(List.of("docker", "build", "--platform", platform(image.architecture), "-t", image.docker.image, context.toString()));
    }

    public static void smoke(ImageSpec image) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("docker", "run", "--rm", "--entrypoint", "/usr/bin/dpkg-query", image.docker.image, "-W"));
        args.addAll(image.guest.smokePackages == null ? List.of() : image.guest.smokePackages);
        run(args);
    }

    static String dockerfile(ImageSpec image) {
        return """
                FROM scratch
                LABEL org.opencontainers.image.source="%s"
                LABEL io.floci.ami.catalog-image-id="%s"
                LABEL io.floci.ami.architecture="%s"
                ADD %s /
                ENV container=docker
                STOPSIGNAL SIGRTMIN+3
                CMD ["/sbin/init"]
                """.formatted(image.canonical.rootfsUrl(), image.catalogImageId, image.architecture, image.canonical.rootfs);
    }

    static String readme(ImageSpec image) {
        return """
                # %s

                Rebuild with:

                ```text
                ./mvnw -q -DskipTests compile exec:java -Dexec.mainClass=%s -Dexec.args="generate --image-id %s"
                ./mvnw -q -DskipTests compile exec:java -Dexec.mainClass=%s -Dexec.args="build --image-id %s"
                ./mvnw -q -DskipTests compile exec:java -Dexec.mainClass=%s -Dexec.args="smoke --image-id %s"
                ```
                """.formatted(image.id, AmiImageTool.class.getName(), image.id,
                AmiImageTool.class.getName(), image.id, AmiImageTool.class.getName(), image.id);
    }

    static Path contextDir(Path outputRoot, ImageSpec image) {
        return outputRoot.resolve(image.id);
    }

    private static ImageSpec findImage(Metadata metadata, String id) {
        return metadata.images.stream()
                .filter(image -> id.equals(image.id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AMI image id in metadata: " + id));
    }

    private static void validate(Metadata metadata) {
        required(metadata.releaseId, "releaseId");
        if (metadata.images == null || metadata.images.isEmpty()) {
            throw new IllegalArgumentException("metadata must declare at least one image");
        }
        metadata.images.forEach(AmiImageTool::validateImage);
    }

    private static void validateImage(ImageSpec image) {
        required(image.id, "id");
        required(image.catalogImageId, "catalogImageId");
        required(image.family, "family");
        required(image.version, "version");
        required(image.architecture, "architecture");
        Objects.requireNonNull(image.aws, "aws");
        Objects.requireNonNull(image.canonical, "canonical");
        Objects.requireNonNull(image.docker, "docker");
        Objects.requireNonNull(image.guest, "guest");
        required(image.aws.region, "aws.region");
        required(image.aws.ownerId, "aws.ownerId");
        required(image.aws.imageId, "aws.imageId");
        required(image.aws.name, "aws.name");
        required(image.aws.creationDate, "aws.creationDate");
        required(image.aws.virtualizationType, "aws.virtualizationType");
        required(image.aws.rootDeviceType, "aws.rootDeviceType");
        required(image.canonical.baseUrl, "canonical.baseUrl");
        required(image.canonical.rootfs, "canonical.rootfs");
        required(image.canonical.rootfsSha256, "canonical.rootfsSha256");
        required(image.canonical.manifest, "canonical.manifest");
        required(image.docker.image, "docker.image");
        required(image.guest.runtime, "guest.runtime");
        if (image.canonical.rootfs.contains(":") || isDockerLibraryUbuntu2404(image.docker.image)) {
            throw new IllegalArgumentException("AMI guest images must not use Docker-library ubuntu:24.04 as source");
        }
    }

    private static boolean isDockerLibraryUbuntu2404(String image) {
        return switch (image) {
            case "ubuntu:24.04",
                    "library/ubuntu:24.04",
                    "docker.io/ubuntu:24.04",
                    "docker.io/library/ubuntu:24.04",
                    "public.ecr.aws/docker/library/ubuntu:24.04" -> true;
            default -> false;
        };
    }

    private static String awsArchitecture(String architecture) {
        return "amd64".equals(architecture) ? "x86_64" : architecture;
    }

    private static String platform(String architecture) {
        return switch (architecture) {
            case "arm64" -> "linux/arm64";
            case "amd64", "x86_64" -> "linux/amd64";
            default -> throw new IllegalArgumentException("Unsupported Docker platform architecture: " + architecture);
        };
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required AMI image metadata field: " + field);
        }
    }

    private static Path download(String uri, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            return target;
        }
        URI source = URI.create(uri);
        if ("file".equals(source.getScheme())) {
            Files.copy(Path.of(source), target);
            return target;
        }
        HttpRequest request = HttpRequest.newBuilder(source).GET().build();
        try {
            HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
            }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + uri, e);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(new java.io.OutputStream() {
                    @Override
                    public void write(int b) {
                        digest.update((byte) b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        digest.update(b, off, len);
                    }
                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Command failed with exit " + exit + ": " + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not run command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running command: " + String.join(" ", command), e);
        }
    }

    private record Options(String command, Path metadata, Path output, Path catalog, String imageId) {
        static Options parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("usage: AmiImageTool <plan|resolve|generate|update-catalog|build|smoke> [--image-id id] [--metadata file]");
            }
            String command = args[0];
            Path metadata = DEFAULT_METADATA;
            Path output = DEFAULT_OUTPUT;
            Path catalog = Path.of("src/main/resources/ec2/image-catalog.yaml");
            String imageId = null;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--metadata" -> metadata = Path.of(args[++i]);
                    case "--output" -> output = Path.of(args[++i]);
                    case "--catalog" -> catalog = Path.of(args[++i]);
                    case "--image-id" -> imageId = args[++i];
                    case "--write", "--write-lock" -> { }
                    case "--context", "--tag" -> i++;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new Options(command, metadata, output, catalog, imageId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Metadata {
        public String releaseId;
        public List<ImageSpec> images = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ImageSpec {
        public String id;
        public String catalogImageId;
        public List<String> catalogAliases = List.of();
        public String family;
        public String version;
        public String architecture;
        public Aws aws;
        public Canonical canonical;
        public Docker docker;
        public Guest guest;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Aws {
        public String region;
        public String ownerId;
        public String imageId;
        public String name;
        public String creationDate;
        public String virtualizationType;
        public String rootDeviceType;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Canonical {
        public String baseUrl;
        public String rootfs;
        public String rootfsSha256;
        public String manifest;

        String rootfsUrl() {
            return joinUrl(baseUrl, rootfs);
        }

        String manifestUrl() {
            return joinUrl(baseUrl, manifest);
        }

        private static String joinUrl(String base, String leaf) {
            return base.endsWith("/") ? base + leaf : base + "/" + leaf;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Docker {
        public String image;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Guest {
        public String runtime;
        public boolean cloudInit;
        public List<String> smokePackages = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Catalog {
        public String defaultDockerImage;
        public List<CatalogImage> images = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogImage {
        public String imageId;
        public List<String> aliases = List.of();
        public String dockerImage;
        public String name;
        public String description;
        public String ownerId;
        public String imageOwnerAlias;
        public String architecture;
        public String rootDeviceType;
        public String virtualizationType;
        public String creationDate;
        public String guestRuntime;
        public Boolean cloudInit;
        public String provenance;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Provenance {
        public String imageId;
        public String catalogImageId;
        public String dockerImage;
        public String family;
        public String version;
        public String architecture;
        public Aws aws;
        public String rootfsUrl;
        public String rootfsSha256;
        public String manifestUrl;
        public String manifestSha256;
        public String generatedAt;
    }
}
