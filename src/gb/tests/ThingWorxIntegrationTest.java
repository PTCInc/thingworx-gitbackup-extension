package gb.tests;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class ThingWorxIntegrationTest extends ThingWorxContainerBase {

    @ParameterizedTest(name = "postgresIsRunning [{0}]")
    @MethodSource("thingworxVersions")
    void postgresIsRunning(ThingWorxVersion version) {
        assertTrue(postgres.isRunning(), "PostgreSQL container must be running");
    }

    @ParameterizedTest(name = "dbInitCompleted [{0}]")
    @MethodSource("thingworxVersions")
    void dbInitCompleted(ThingWorxVersion version) {
        var stack = getOrCreateStack(version);
        assertTrue(stack.dbInit.isRunning(), "DB init container must be running");
    }

    @ParameterizedTest(name = "thingworxTablesExist [{0}]")
    @MethodSource("thingworxVersions")
    void thingworxTablesExist(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        var db = "twadmin_" + version.label.replace('.', '_');

        var tables = postgres.execInContainer("psql", "-U", "postgres", "-d", db,
                "-c",
                "SELECT table_schema, table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY table_schema, table_name;");
        assertEquals(0, tables.getExitCode(), "psql query failed: " + tables.getStderr());
        assertFalse(tables.getStdout().isBlank(), "No tables found in " + db + " database");
        assertTrue(tables.getStdout().contains("thing_model"),
                "Expected ThingWorx tables in " + db + " database. Got:\n" + tables.getStdout());
    }

    @ParameterizedTest(name = "platformIsRunning [{0}]")
    @MethodSource("thingworxVersions")
    void platformIsRunning(ThingWorxVersion version) {
        var stack = getOrCreateStack(version);
        assertTrue(stack.platform.isRunning(), "Platform container must be running");
    }

    @ParameterizedTest(name = "thingworxHealthCheck [{0}]")
    @MethodSource("thingworxVersions")
    void thingworxHealthCheck(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        var req = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl + "/Thingworx/health"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        var res = stack.httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "Health endpoint must return 200");
    }

    @ParameterizedTest(name = "installAndVerifyExtension [{0}]")
    @MethodSource("thingworxVersions")
    void installAndVerifyExtension(ThingWorxVersion version) throws Exception {
        var stack = getOrCreateStack(version);
        installExtension(stack, Paths.get("build/distributions/GitBackupExtension.zip"));
        verifyExtensionInstalled(stack);
    }
}
