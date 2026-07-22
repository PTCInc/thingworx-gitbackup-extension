package gb.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GiteaGitOperationsTest extends ThingWorxContainerBase {

    private static final String GITEA_USER = "testadmin";
    private static final String GITEA_PASS = "testadmin123";
    private static final String REPO_NAME = "gitbackup-test";
    private static final String GIT_THING_NAME = "ITGiteaTestThing";
    private static final String GIT_THING_PATH = "/" + GIT_THING_NAME;
    private static final String TEST_FILE = "hello-gitbackup.txt";
    private static final String GITEA_ALIAS = "gitea";

    @Container
    private static final GenericContainer<?> gitea = new GenericContainer<>("gitea/gitea:1.22.3")
            .withNetwork(network)
            .withNetworkAliases(GITEA_ALIAS)
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

    private static final ThingWorxVersion TEST_VERSION = new ThingWorxVersion("9.7.5",
            "devopscadit/postgresql-init-twx:platform9.7.5",
            "devopscadit/platform-postgres:platform9.7.5");

    private HttpClient httpClient;
    private String authHeader;
    private String giteaAuthHeader;
    private String giteaHostUrl;
    private String giteaInternalRepoUrl;
    private String giteaApiRepoPath;
    private Stack stack;
    private Path extensionZipPath = Paths.get("build/distributions/GitBackupExtension.zip");

    @BeforeAll
    void setup() throws Exception {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        authHeader = "Basic " + Base64.getEncoder().encodeToString(
                ("Administrator:" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));
        giteaAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (GITEA_USER + ":" + GITEA_PASS).getBytes(StandardCharsets.UTF_8));

        giteaHostUrl = "http://" + gitea.getHost() + ":" + gitea.getMappedPort(3000);
        giteaInternalRepoUrl = "http://" + GITEA_ALIAS + ":3000/" + GITEA_USER + "/" + REPO_NAME + ".git";
        giteaApiRepoPath = "/api/v1/repos/" + GITEA_USER + "/" + REPO_NAME;

        stack = getOrCreateStack(TEST_VERSION);
        installExtension(stack, extensionZipPath);
        verifyExtensionInstalled(stack);
        createAdminOnGitea();
        createRepoOnGitea();
        callService("GIT.Utility.Thing", "InitUserExtensionProperties", null);
        callService("GIT.Utility.Thing", "InitUserExtensionGpgKeysProperty", null);
    }

    private void createAdminOnGitea() throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                var execResult = gitea.execInContainer("sh", "-c",
                        "su git -c 'gitea admin user create --username " + GITEA_USER
                        + " --password " + GITEA_PASS
                        + " --email admin@example.com"
                        + " --admin'");
                String out = execResult.getStdout() + execResult.getStderr();
                if (execResult.getExitCode() == 0) return;
                if (out.contains("already exists")) return;
                Thread.sleep(2000);
            } catch (Exception e) {
                Thread.sleep(2000);
            }
        }
    }

    private void createRepoOnGitea() throws Exception {
        String json = "{\"name\":\"" + REPO_NAME + "\",\"auto_init\":true,\"private\":false}";
        Exception lastError = null;
        for (int i = 0; i < 30; i++) {
            try {
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(giteaHostUrl + "/api/v1/user/repos"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", giteaAuthHeader)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 201 || res.statusCode() == 200) return;
                if (res.statusCode() == 409) return;
                lastError = new RuntimeException("Gitea repo create returned: " + res.statusCode() + " " + res.body());
                Thread.sleep(2000);
            } catch (Exception e) {
                lastError = e;
                Thread.sleep(2000);
            }
        }
        throw lastError != null ? lastError : new RuntimeException("Failed to create Gitea repo");
    }

    private void createBranchOnGitea(String branchName) throws Exception {
        String json = "{\"new_branch_name\":\"" + branchName + "\",\"old_ref_name\":\"main\"}";
        var req = HttpRequest.newBuilder()
                .uri(URI.create(giteaHostUrl + giteaApiRepoPath + "/branches"))
                .header("Content-Type", "application/json")
                .header("Authorization", giteaAuthHeader)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();
        var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(res.statusCode() == 201 || res.statusCode() == 200 || res.statusCode() == 409,
                "Create branch on Gitea failed: " + res.statusCode() + " " + res.body());
    }

    private HttpResponse<String> callService(String thingName, String serviceName, String body)
            throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl + "/Thingworx/Things/" + thingName + "/Services/" + serviceName))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .header("X-XSRF-TOKEN", "TWX-XSRF-TOKEN-VALUE")
                .header("X-Requested-By", "ThingWorx")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body))
                .timeout(Duration.ofMinutes(3))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void callPutFile(String repoName, String path, String content) throws Exception {
        String json = "{\"path\":\"" + path + "\",\"content\":\"" + escapeJson(content) + "\"}";
        var res = callService(repoName, "SaveText", json);
        assertTrue(res.statusCode() == 200 || res.statusCode() == 201,
                "SaveText failed: " + res.statusCode() + " " + res.body());
    }

    @Test
    @Order(1)
    void testCreateGitThing() throws Exception {
        String addNewRepoBody = "{"
                + "\"RepoName\":\"" + GIT_THING_NAME + "\","
                + "\"GitRepoURL\":\"" + giteaInternalRepoUrl + "\","
                + "\"RepoPath\":\"" + GIT_THING_PATH + "\","
                + "\"FileRepo\":\"GitRepository\","
                + "\"User\":\"" + GITEA_USER + "\","
                + "\"Password\":\"" + GITEA_PASS + "\","
                + "\"CommitUser\":\"Test User\","
                + "\"CommitEmail\":\"test@example.com\","
                + "\"InitialBranch\":\"main\","
                + "\"ProxyURL\":\"\","
                + "\"ProxyPort\":0,"
                + "\"UseProxy\":false,"
                + "\"LocalizationTokensPrefix\":\"\""
                + "}";

        var res = callService("GIT.Utility.Thing", "AddNewRepo", addNewRepoBody);
        assertTrue(res.statusCode() == 200 || res.statusCode() == 201,
                "AddNewRepo failed: " + res.statusCode() + " " + res.body());

        Thread.sleep(5000);

        var verifyRes = callService(GIT_THING_NAME, "GetCurrentBranch", null);
        assertTrue(verifyRes.statusCode() == 200 || verifyRes.statusCode() == 201,
                "Git thing was not created successfully: " + verifyRes.statusCode() + " " + verifyRes.body());
    }

    @Test
    @Order(2)
    void testPush() throws Exception {
        callPutFile("GitRepository", GIT_THING_PATH + "/" + TEST_FILE,
                "Hello from ThingWorx GitBackup integration test!");

        var pushRes = callService(GIT_THING_NAME, "Push",
                "{\"Message\":\"Integration test: initial commit\"}");
        assertEquals(200, pushRes.statusCode(), "Push failed: " + pushRes.body());
        assertNotNull(pushRes.body());
        String body = pushRes.body();
        assertFalse(body.contains("Error"), "Push returned error: " + body);
        assertFalse(body.contains("Exception"), "Push threw exception: " + body);
    }

    @Test
    @Order(3)
    void testStatus() throws Exception {
        var res = callService(GIT_THING_NAME, "Status", null);
        assertEquals(200, res.statusCode(), "Status failed: " + res.body());
        assertNotNull(res.body());
        assertTrue(res.body().contains("rows"),
                "Status should return infotable JSON: " + res.body());
    }

    @Test
    @Order(4)
    void testPull() throws Exception {
        var res = callService(GIT_THING_NAME, "Pull", "{\"Force\":false}");
        assertEquals(200, res.statusCode(), "Pull failed: " + res.body());
        assertNotNull(res.body());
        assertFalse(res.body().contains("Error"), "Pull returned error: " + res.body());
    }

    @Test
    @Order(5)
    void testCheckoutMain() throws Exception {
        var res = callService(GIT_THING_NAME, "Checkout",
                "{\"BranchNameOrCommit\":\"main\"}");
        assertEquals(200, res.statusCode(), "Checkout to main failed: " + res.body());
    }

    @Test
    @Order(6)
    void testGetCurrentBranch() throws Exception {
        var res = callService(GIT_THING_NAME, "GetCurrentBranch", null);
        assertEquals(200, res.statusCode(), "GetCurrentBranch failed: " + res.body());
        assertNotNull(res.body());
        assertTrue(res.body().contains("BranchName") || res.body().contains("main"),
                "Response should indicate current branch: " + res.body());
    }

    @Test
    @Order(7)
    void testCheckoutRemoteBranch() throws Exception {
        createBranchOnGitea("it-test-feature");

        Thread.sleep(2000);

        var pullRes = callService(GIT_THING_NAME, "Pull", "{\"Force\":false}");
        assertEquals(200, pullRes.statusCode(), "Pull before checkout failed: " + pullRes.body());

        var checkoutRes = callService(GIT_THING_NAME, "Checkout",
                "{\"BranchNameOrCommit\":\"it-test-feature\"}");
        assertEquals(200, checkoutRes.statusCode(),
                "Checkout to remote tracking branch failed: " + checkoutRes.body());

        callPutFile("GitRepository", GIT_THING_PATH + "/feature-file.txt",
                "Feature branch content");

        var pushRes = callService(GIT_THING_NAME, "Push",
                "{\"Message\":\"Feature branch commit\"}");
        assertEquals(200, pushRes.statusCode(), "Push on feature branch failed: " + pushRes.body());

        var currentBranchRes = callService(GIT_THING_NAME, "GetCurrentBranch", null);
        assertTrue(currentBranchRes.body().contains("it-test-feature"),
                "Should be on feature branch: " + currentBranchRes.body());

        var checkoutMainRes = callService(GIT_THING_NAME, "Checkout",
                "{\"BranchNameOrCommit\":\"main\"}");
        assertEquals(200, checkoutMainRes.statusCode(), "Checkout back to main failed: " + checkoutMainRes.body());
    }

    @Test
    @Order(8)
    void testBranchList() throws Exception {
        var res = callService(GIT_THING_NAME, "GetBranchList", null);
        assertEquals(200, res.statusCode(), "GetBranchList failed: " + res.body());
        assertNotNull(res.body());

        String body = res.body();
        assertTrue(body.contains("main"), "Should contain main branch: " + body);
        assertTrue(body.contains("it-test-feature"), "Should contain feature branch: " + body);
    }

    @Test
    @Order(9)
    void testDeleteLocalBranch() throws Exception {
        createBranchOnGitea("it-temp-branch");

        Thread.sleep(2000);

        var pullRes = callService(GIT_THING_NAME, "Pull", "{\"Force\":false}");
        assertEquals(200, pullRes.statusCode(), "Pull before checkout failed: " + pullRes.body());

        var checkoutRes = callService(GIT_THING_NAME, "Checkout",
                "{\"BranchNameOrCommit\":\"it-temp-branch\"}");
        assertEquals(200, checkoutRes.statusCode(),
                "Checkout to temp branch failed: " + checkoutRes.body());

        var switchBackRes = callService(GIT_THING_NAME, "Checkout",
                "{\"BranchNameOrCommit\":\"main\"}");
        assertEquals(200, switchBackRes.statusCode(), "Switch back to main failed: " + switchBackRes.body());

        var deleteRes = callService(GIT_THING_NAME, "DeleteLocalBranch",
                "{\"BranchName\":\"it-temp-branch\"}");
        assertEquals(200, deleteRes.statusCode(), "DeleteLocalBranch failed: " + deleteRes.body());

        Thread.sleep(2000);

        var branchListRes = callService(GIT_THING_NAME, "GetBranchList", null);
        assertFalse(branchListRes.body().contains("refs/heads/it-temp-branch"),
                "Local branch it-temp-branch should have been deleted: " + branchListRes.body());
    }

    @Test
    @Order(10)
    void testCommitList() throws Exception {
        var res = callService(GIT_THING_NAME, "GetCommitList", null);
        assertEquals(200, res.statusCode(), "GetCommitList failed: " + res.body());
        assertNotNull(res.body());
        assertTrue(res.body().contains("rows"),
                "Should contain rows: " + res.body());
    }

    @Test
    @Order(11)
    void testCommitInfo() throws Exception {
        var commitListRes = callService(GIT_THING_NAME, "GetCommitList", null);
        assertEquals(200, commitListRes.statusCode());

        var firstCommitId = extractFirstCommitId(commitListRes.body());
        if (firstCommitId == null) {
            return;
        }

        var res = callService(GIT_THING_NAME, "GetCommitInfo",
                "{\"CommitID\":\"" + firstCommitId + "\"}");
        assertEquals(200, res.statusCode(), "GetCommitInfo failed: " + res.body());
        assertNotNull(res.body());
        assertTrue(res.body().contains(firstCommitId) || res.body().contains("CommitID"),
                "Commit info should reference the commit: " + res.body());
    }

    @Test
    @Order(12)
    void testDiffPerFileBetweenCommits() throws Exception {
        var commitListRes = callService(GIT_THING_NAME, "GetCommitList", null);
        assertEquals(200, commitListRes.statusCode());

        var firstCommitId = extractFirstCommitId(commitListRes.body());
        if (firstCommitId == null) {
            return;
        }

        var res = callService(GIT_THING_NAME, "GetDiffPerFileBetweenCommits",
                "{\"File\":\"" + TEST_FILE + "\",\"FromCommitID\":\"" + firstCommitId + "\"}");
        assertEquals(200, res.statusCode(), "GetDiffPerFileBetweenCommits failed: " + res.body());
        assertNotNull(res.body());
    }

    @Test
    @Order(13)
    void testDiffPerFile() throws Exception {
        var res = callService(GIT_THING_NAME, "GetDiffPerFile",
                "{\"File\":\"" + TEST_FILE + "\"}");
        assertEquals(200, res.statusCode(), "GetDiffPerFile failed: " + res.body());
        assertNotNull(res.body());
    }

    @Test
    @Order(14)
    void testForcePull() throws Exception {
        var res = callService(GIT_THING_NAME, "Pull", "{\"Force\":true}");
        assertEquals(200, res.statusCode(), "Force pull failed: " + res.body());
        assertFalse(res.body().contains("Error"), "Force pull returned error: " + res.body());
    }

    @Test
    @Order(15)
    void testVerifyGpgKey() throws Exception {
        String testKey = generateTestGpgPrivateKey();
        var setKeyRes = callService("GIT.Utility.Thing", "SetGpgKey",
                "{\"GitThing\":\"" + GIT_THING_NAME + "\","
                + "\"GpgPrivateKey\":\"" + escapeJson(testKey) + "\","
                + "\"SignCommits\":false,"
                + "\"GpgKeyFingerprint\":\"\"}");
        assertTrue(setKeyRes.statusCode() == 200 || setKeyRes.statusCode() == 201,
                "SetGpgKey failed: " + setKeyRes.statusCode() + " " + setKeyRes.body());

        Thread.sleep(2000);

        var res = callService(GIT_THING_NAME, "VerifyGpgKey",
                "{\"GpgPrivateKey\":\"\",\"GpgKeyPassphrase\":\"\"}");
        assertEquals(200, res.statusCode(), "VerifyGpgKey failed: " + res.body());
        assertTrue(res.body().contains("GpgKeyFingerprint"),
                "VerifyGpgKey should return fingerprint: " + res.body());
    }

    @Test
    @Order(16)
    void testSignedPush() throws Exception {
        callPutFile("GitRepository", GIT_THING_PATH + "/signed-test.txt",
                "Signed commit test content");

        String testKey = generateTestGpgPrivateKey();

        var setKeyRes = callService("GIT.Utility.Thing", "SetGpgKey",
                "{\"GitThing\":\"" + GIT_THING_NAME + "\","
                + "\"GpgPrivateKey\":\"" + escapeJson(testKey) + "\","
                + "\"SignCommits\":true,"
                + "\"GpgKeyFingerprint\":\"\"}");
        assertTrue(setKeyRes.statusCode() == 200 || setKeyRes.statusCode() == 201,
                "SetGpgKey failed: " + setKeyRes.statusCode() + " " + setKeyRes.body());

        Thread.sleep(2000);

        var pushRes = callService(GIT_THING_NAME, "Push",
                "{\"Message\":\"Integration test: signed commit\"}");
        assertEquals(200, pushRes.statusCode(), "Signed Push failed: " + pushRes.body());
        String body = pushRes.body();
        assertFalse(body.contains("Error"), "Signed Push returned error: " + body);
        assertFalse(body.contains("Exception"), "Signed Push threw exception: " + body);

        var commitListRes = callService(GIT_THING_NAME, "GetCommitList", null);
        assertTrue(commitListRes.body().contains("rows"),
                "Commit list should have entries: " + commitListRes.body());
    }

    @Test
    @Order(17)
    void testDeleteLocalRepoContent() throws Exception {
        var res = callService(GIT_THING_NAME, "DeleteLocalRepoContent", null);
        assertEquals(200, res.statusCode(), "DeleteLocalRepoContent failed: " + res.body());

        var pullRes = callService(GIT_THING_NAME, "Pull", "{\"Force\":false}");
        assertEquals(200, pullRes.statusCode(), "Pull after delete failed: " + pullRes.body());
        assertFalse(pullRes.body().contains("Error"),
                "Pull after content deletion returned error: " + pullRes.body());
    }

    private String extractFirstCommitId(String json) {
        int rowsIdx = json.indexOf("\"rows\"");
        if (rowsIdx == -1) return null;
        int commitIdIdx = json.indexOf("\"CommitID\"", rowsIdx);
        if (commitIdIdx == -1) return null;
        int colonIdx = json.indexOf(":", commitIdIdx);
        if (colonIdx == -1) return null;
        int quote1 = json.indexOf("\"", colonIdx);
        if (quote1 == -1) return null;
        int quote2 = json.indexOf("\"", quote1 + 1);
        if (quote2 == -1) return null;
        return json.substring(quote1 + 1, quote2);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String generateTestGpgPrivateKey() throws Exception {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(1024);
        java.security.KeyPair kp = kpg.generateKeyPair();

        String userId = "Test User <test@example.com>";

        org.bouncycastle.openpgp.PGPKeyPair pgpKeyPair = new org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair(
                org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_SIGN, kp, new java.util.Date());

        org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder digestProvBuilder =
                new org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder().setProvider("BC");
        org.bouncycastle.openpgp.operator.PGPDigestCalculator sha1Calc = digestProvBuilder.build()
                .get(org.bouncycastle.openpgp.PGPUtil.SHA1);

        org.bouncycastle.openpgp.PGPKeyRingGenerator keyRingGen = new org.bouncycastle.openpgp.PGPKeyRingGenerator(
                org.bouncycastle.openpgp.PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                sha1Calc,
                null, null,
                new org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder(
                        pgpKeyPair.getPublicKey().getAlgorithm(), org.bouncycastle.openpgp.PGPUtil.SHA256).setProvider("BC"),
                null);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (org.bouncycastle.bcpg.ArmoredOutputStream armoredOut = new org.bouncycastle.bcpg.ArmoredOutputStream(out)) {
            keyRingGen.generateSecretKeyRing().encode(armoredOut);
        }
        return out.toString("UTF-8");
    }
}
