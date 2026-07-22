package gb.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invokes the Entity-defined JavaScript tests (GitBackup.Tests.Thing) via the
 * ThingWorx REST API, using the same test container infrastructure as
 * ThingWorxIntegrationTest and GiteaGitOperationsTest.
 *
 * The Entities tests run inside the ThingWorx platform. They are called through
 * the ThingWorx REST API via the Services on GitBackup.Tests.Thing.
 *
 * === Configuration Required ===
 * The {@code CreateTestData} service in
 * {@code Entities/Things_GitBackup.Tests.Thing.xml} has "UPDATE" placeholders
 * for GitHub credentials (URL, user, token, etc.). Before
 * {@link #invokeEntitiesTestService} can pass, edit that file and fill in the
 * actual values:
 * <ul>
 *   <li>GITTestRepoURL - your GitHub test repo URL</li>
 *   <li>GitUser - your GitHub username</li>
 *   <li>GitPassword - a GitHub Personal Access Token</li>
 *   <li>GitCommitterName / GitComitterEmail - commit author info</li>
 *   <li>GITTestRepoURLCommits - the GitHub API commits URL</li>
 * </ul>
 *
 * Alternatively, manually invoke individual test services via REST as shown in
 * {@link GiteaGitOperationsTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ThingWorxEntitiesTest extends ThingWorxContainerBase {

    private static final String GITEA_USER = "testadmin";
    private static final String GITEA_PASS = "testadmin123";
    private static final String REPO_NAME = "gitbackup-test";

    @Container
    private static final GenericContainer<?> gitea = new GenericContainer<>("gitea/gitea:1.22.3")
            .withNetwork(network)
            .withNetworkAliases("gitea")
            .withEnv("GITEA__security__INSTALL_LOCK", "true")
            .withEnv("GITEA__admin__NAME", GITEA_USER)
            .withEnv("GITEA__admin__PASSWD", GITEA_PASS)
            .withEnv("GITEA__admin__EMAIL", "admin@example.com")
            .withEnv("GITEA__server__DOMAIN", "localhost")
            .withEnv("GITEA__server__HTTP_PORT", "3000")
            .withEnv("GITEA__server__ROOT_URL", "http://localhost:3000/")
            .withEnv("GITEA__database__DB_TYPE", "sqlite3")
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private String giteaHostUrl;
    private String giteaAuthHeader;

    @BeforeAll
    void setupGitea() throws Exception {
        giteaHostUrl = "http://" + gitea.getHost() + ":" + gitea.getMappedPort(3000);
        giteaAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (GITEA_USER + ":" + GITEA_PASS).getBytes());

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String json = "{\"name\":\"" + REPO_NAME + "\",\"auto_init\":true,\"private\":false}";
        for (int i = 0; i < 15; i++) {
            try {
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(giteaHostUrl + "/api/v1/user/repos"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", giteaAuthHeader)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 201 || res.statusCode() == 200 || res.statusCode() == 409) {
                    break;
                }
            } catch (Exception e) {
                Thread.sleep(3000);
            }
        }
    }

    @ParameterizedTest(name = "invokeEntitiesTestService [{0}]")
    @MethodSource("thingworxVersions")
    void invokeEntitiesTestService(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        installExtension(stack, Paths.get("build/distributions/GitBackupExtension.zip"));
        verifyExtensionInstalled(stack);

        var auth = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes());

        var res = callService(stack, auth, "GitBackup.Tests.Thing", "RunAllTests", "{}",
                Duration.ofMinutes(10));
        assertEquals(200, res.statusCode(),
                "RunAllTests HTTP status. Body: " + res.body());

        assertNotNull(res.body(), "RunAllTests response body must not be null");
        assertFalse(res.body().contains("\"passed\":false"),
                "All Entities tests should pass. Response: " + res.body());
    }

    @ParameterizedTest(name = "entitiesTestServicesRespond [{0}]")
    @MethodSource("thingworxVersions")
    void entitiesTestServicesRespond(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        installExtension(stack, Paths.get("build/distributions/GitBackupExtension.zip"));
        verifyExtensionInstalled(stack);

        var auth = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes());

        var servicesToCheck = new String[]{
                "CreateGitBackupThingTest",
                "PullTest",
                "DeleteLocalRepoContent",
                "DeleteLocalRepoContentAfterPush",
                "PushTest",
                "CreateTestData",
                "DeleteTestData",
                "RunAllTests"
        };

        for (var service : servicesToCheck) {
            var res = callService(stack, auth, "GitBackup.Tests.Thing", service, "{}",
                    Duration.ofSeconds(30));
            assertTrue(res.statusCode() == 200 || res.statusCode() == 500,
                    "Service " + service + " should be reachable. Status: "
                            + res.statusCode() + " Body: " + res.body());
        }
    }

    @ParameterizedTest(name = "createTestDataReturnsConfig [{0}]")
    @MethodSource("thingworxVersions")
    void createTestDataReturnsConfig(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        installExtension(stack, Paths.get("build/distributions/GitBackupExtension.zip"));

        var auth = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes());

        var res = callService(stack, auth, "GitBackup.Tests.Thing", "CreateTestData", "{}",
                Duration.ofSeconds(30));
        assertEquals(200, res.statusCode(), "CreateTestData HTTP status. Body: " + res.body());
        assertNotNull(res.body());
        assertTrue(res.body().contains("tests"),
                "CreateTestData should return a JSON with 'tests' array. Body: " + res.body());
    }

    /** Convenience: invoke a service on a ThingWorx Thing via REST. */
    private static HttpResponse<String> callService(Stack stack, String auth,
            String thingName, String serviceName, String body, Duration timeout)
            throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl + "/Thingworx/Things/" + thingName
                        + "/Services/" + serviceName))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", auth)
                .header("X-XSRF-TOKEN", "TWX-XSRF-TOKEN-VALUE")
                .header("X-Requested-By", "ThingWorx")
                .POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"))
                .timeout(timeout)
                .build();
        return stack.httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
