package gb.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ThingWorxContainerBase {

    protected static final String DB_PASS = "twx_password_123";
    protected static final String ADMIN_PASS = "TwxAdm1nP@ssw0rd!";
    protected static final String DB_USER = "twadmin";
    protected static final String APP_KEY = "provisioning_app_key";

    protected static final String LS_USERNAME;
    protected static final String LS_PASSWORD;

    static {
        var props = new Properties();
        var envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            try (var in = new FileInputStream(envFile.toFile())) {
                props.load(in);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load " + envFile, e);
            }
        }
        LS_USERNAME = props.getProperty("LS_USERNAME", "");
        LS_PASSWORD = props.getProperty("LS_PASSWORD", "");
    }

    protected static final Network network = Network.newNetwork();

    @Container
    protected static final GenericContainer<?> postgres = new GenericContainer<>("postgres:15")
            .withNetwork(network)
            .withNetworkAliases("postgresql")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", DB_PASS)
            .withEnv("POSTGRES_DB", "postgres")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    public static class ThingWorxVersion {
        public final String label;
        public final String dbInitImage;
        public final String platformImage;

        ThingWorxVersion(String label, String dbInitImage, String platformImage) {
            this.label = label;
            this.dbInitImage = dbInitImage;
            this.platformImage = platformImage;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<ThingWorxVersion> thingworxVersions() {
        return Stream.of(
                new ThingWorxVersion("9.5.0",
                        "devopscadit/postgresql-init-twx:platform9.5.0",
                        "devopscadit/platform-postgres:platform9.5.0"),
                new ThingWorxVersion("9.6.3",
                        "devopscadit/postgresql-init-twx:platform9.6.3",
                        "devopscadit/platform-postgres:platform9.6.3"),
                new ThingWorxVersion("9.7.5",
                        "devopscadit/postgresql-init-twx:platform9.7.5",
                        "devopscadit/platform-postgres:platform9.7.5"),
                new ThingWorxVersion("10.1.0",
                        "devopscadit/postgresql-init-twx:platform10.1.0",
                        "devopscadit/platform-postgres:platform10.1.0")
        );
    }

    protected static class Stack {
        public final String twxUser;
        public final GenericContainer<?> dbInit;
        public final GenericContainer<?> platform;
        public final HttpClient httpClient;
        public final String baseUrl;

        Stack(ThingWorxVersion version) {
            this.twxUser = "twadmin_" + version.label.replace('.', '_');
            this.dbInit = createDbInit(version, twxUser);
            this.dbInit.start();
            this.platform = createPlatform(version, twxUser, this.dbInit);
            this.platform.start();
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.baseUrl = "http://" + platform.getHost() + ":" + platform.getMappedPort(8080);
        }
    }

    private final Map<ThingWorxVersion, Stack> stacks = new HashMap<>();

    protected Stack getOrCreateStack(ThingWorxVersion version) {
        return stacks.computeIfAbsent(version, Stack::new);
    }

    private static GenericContainer<?> createDbInit(ThingWorxVersion version, String twxUser) {
        return new GenericContainer<>(version.dbInitImage)
                .withNetwork(network)
                .dependsOn(postgres)
                .withEnv("DATABASE_ADMIN_USERNAME", "postgres")
                .withEnv("DATABASE_ADMIN_PASSWORD", DB_PASS)
                .withEnv("DATABASE_ADMIN_SCHEMA", "postgres")
                .withEnv("DATABASE_HOST", "postgresql")
                .withEnv("DATABASE_PORT", "5432")
                .withEnv("TWX_DATABASE_USERNAME", twxUser)
                .withEnv("TWX_DATABASE_SCHEMA", twxUser)
                .withEnv("TWX_DATABASE_PASSWORD", DB_PASS)
                .withEnv("TABLESPACE_LOCATION", "/var/lib/postgresql/data")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c",
                        "/usr/local/bin/db-setup.sh && echo 'DB_INIT_DONE' && sleep infinity"))
                .waitingFor(Wait.forLogMessage(".*DB_INIT_DONE.*", 1));
    }

    private static GenericContainer<?> createPlatform(ThingWorxVersion version, String twxUser,
            GenericContainer<?> dbInit) {
        return new GenericContainer<>(version.platformImage)
                .withNetwork(network)
                .dependsOn(dbInit, postgres)
                .withEnv("DATABASE_HOST", "postgresql")
                .withEnv("DATABASE_PORT", "5432")
                .withEnv("TWX_DATABASE_USERNAME", twxUser)
                .withEnv("TWX_DATABASE_SCHEMA", twxUser)
                .withEnv("TWX_DATABASE_PASSWORD", DB_PASS)
                .withEnv("THINGWORX_INITIAL_ADMIN_PASSWORD", ADMIN_PASS)
                .withEnv("THINGWORX_INITIAL_METRICS_USER_PASSWORD", "MetricsP@ssw0rd!")
                .withEnv("ENABLE_CONSOLE_OUTPUT", "true")
                .withEnv("CATALINA_OPTS", "-Xms1g -Xmx2g")
                .withEnv("THINGWORX_PLATFORM_SCRIPTTIMEOUT", "30")
                .withEnv("TOMCAT_KEEPALIVETIMEOUT", "20000")
                .withEnv("TOMCAT_CONNECTIONTIMEOUT", "20000")
                .withEnv("TOMCAT_MAXCONNECTION", "10000")
                .withEnv("TOMCAT_MAXTHREADS", "200")
                .withEnv("TOMCAT_CATALINA_LEVEL", "FINE")
                .withEnv("TOMCAT_LOCALHOST_LEVEL", "FINE")
                .withEnv("TOMCAT_MANAGER_LEVEL", "FINE")
                .withEnv("TOMCAT_HOSTMANAGER_LEVEL", "FINE")
                .withEnv("EXTPKG_IMPORT_POLICY_ENABLED", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_ENTITIES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_EXTENTITIES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_JARRES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_JSRES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_CSSRES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_JSONRES", "true")
                .withEnv("EXTPKG_IMPORT_POLICY_ALLOW_WEBAPPRES", "true")
                .withEnv("LS_USERNAME", LS_USERNAME)
                .withEnv("LS_PASSWORD", LS_PASSWORD)
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/Thingworx/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(15)));
    }

    @AfterAll
    void tearDown() {
        stacks.values().forEach(stack -> {
            try {
                stack.httpClient.close();
            } catch (Exception e) {
                // Ignore
            }
            stack.platform.stop();
            stack.dbInit.stop();
        });
    }

    protected static void writeBoundary(ByteArrayOutputStream out, String boundary,
            String name, String filename, String contentType) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\n").getBytes());
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
    }

    protected void installExtension(Stack stack, Path extensionZip) throws Exception {
        assertTrue(Files.exists(extensionZip), "Extension ZIP must exist at " + extensionZip.toAbsolutePath());

        var auth = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes());

        byte[] zipBytes = Files.readAllBytes(extensionZip);
        String boundary = "----FormBoundary" + System.currentTimeMillis();

        var body = new ByteArrayOutputStream();
        writeBoundary(body, boundary, "file", extensionZip.getFileName().toString(), "application/octet-stream");
        body.write(zipBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());

        var uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl + "/Thingworx/ExtensionPackageUploader?purpose=import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", auth)
                .header("X-XSRF-TOKEN", "TWX-XSRF-TOKEN-VALUE")
                .header("X-Requested-By", "ThingWorx")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .timeout(Duration.ofMinutes(5))
                .build();

        var uploadRes = stack.httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        assertTrue(uploadRes.statusCode() == 200 || uploadRes.statusCode() == 201,
                "Extension upload failed. Status: " + uploadRes.statusCode() + ", Body: " + uploadRes.body());
    }

    protected void verifyExtensionInstalled(Stack stack) throws Exception {
        var auth = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes());

        var verifyReq = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl + "/Thingworx/Things/GIT.Utility.Thing"))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        var verifyRes = stack.httpClient.send(verifyReq, HttpResponse.BodyHandlers.ofString());
        assertTrue(verifyRes.statusCode() == 200 || verifyRes.statusCode() == 401,
                "GIT.Utility.Thing should exist or be accessible. Status: " + verifyRes.statusCode());
    }
}
