package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact-promote atom, started the way the runner starts it, against a repository fake that can accept writes,
 * refuse them, or store something else — all three are tested here.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made: the two artifacts arrive and are verified by digest,
 * each side gets its own credentials, {@code targetPath} and {@code deleteSource} are honoured, and a 404, a refused
 * write, a corrupted copy and a bad input each get the message they deserve.
 */
class ArtifactPromoteIT {

    private static final String JAR = "com/acme/app/1.2.3/app-1.2.3.jar";
    private static final String POM = "com/acme/app/1.2.3/app-1.2.3.pom";

    private Atom atom;
    private FakeRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("two artifacts are promoted, verified by digest, each side with its own credentials")
    void promotesAndVerifies() throws Exception {
        repository = FakeRepository.start();
        AtomResult run = promote(JAR + "," + POM, "");

        run.assertOk();
        assertEquals("2", run.field("promoted"), "count: " + run.file("output.json"));
        assertEquals("true", run.field("verified"), "verify flag: " + run.file("output.json"));
        assertEquals("jar-bytes", repository.text("release/" + JAR).strip(), "the jar did not arrive");
        assertEquals("<project/>", repository.text("release/" + POM).strip(), "the pom did not arrive");

        // the two repositories get their own credentials
        List<Map<String, String>> log = repository.log();
        Map<String, String> read = log.stream()
                .filter(entry -> "GET".equals(entry.get("method")) && entry.get("path").startsWith("staging/"))
                .findFirst().orElseThrow(() -> new AssertionError("no read from staging: " + log));
        Map<String, String> write = log.stream()
                .filter(entry -> "PUT".equals(entry.get("method")))
                .findFirst().orElseThrow(() -> new AssertionError("no write: " + log));
        assertEquals(basic("ci:source-token"), read.get("auth"), "the source must use the source token");
        assertEquals(basic("ci:target-token"), write.get("auth"), "the target must use the target token");
    }

    @Test
    @DisplayName("targetPath moves the artifact under another directory in the target repository")
    void honoursTargetPath() throws Exception {
        repository = FakeRepository.start();
        AtomResult run = promote(JAR, "\"targetPath\": \"releases/acme\",");

        run.assertOk();
        assertEquals("jar-bytes", repository.text("release/releases/acme/app-1.2.3.jar").strip(),
                "targetPath was ignored: " + run.file("output.json"));
    }

    @Test
    @DisplayName("deleteSource removes the staging copy after a verified promotion, and leaves the target")
    void deletesTheSourceWhenAsked() throws Exception {
        repository = FakeRepository.start();
        AtomResult run = promote(POM, "\"deleteSource\": \"true\",");

        run.assertOk();
        assertEquals("<project/>", repository.text("release/" + POM).strip(), "the target should stay");
        assertNull(repository.text("staging/" + POM), "the staging copy should be gone");
    }

    @Test
    @DisplayName("a path that is not there names the repository, and a 404 is a user error")
    void reportsAMissingArtifact() throws Exception {
        repository = FakeRepository.start();
        AtomResult run = promote("com/acme/app/9.9.9/missing.jar", "");

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("cannot read com/acme/app/9.9.9/missing.jar from staging"), "message: " + output);
        assertTrue(output.contains("\"errorType\":1"), "a 404 is a USER error: " + output);
    }

    @Test
    @DisplayName("a repository that refuses writes says the token lacks deploy rights")
    void explainsARefusedWrite() throws Exception {
        repository = FakeRepository.start(true, false);
        AtomResult run = promote(JAR, "");

        run.assertRefused();
        assertTrue(run.file("output.json").contains("rights on release"),
                "403 hint: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a repository that accepts the write but stores something else is caught by the digest")
    void catchesACorruptedCopy() throws Exception {
        repository = FakeRepository.start(false, true);
        AtomResult run = promote(JAR, "");

        run.assertRefused();
        assertTrue(run.file("output.json").contains("differs from the source"),
                "digest mismatch message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("with verify off the corruption is not even noticed, which is the point of the default")
    void doesNotCompareDigestsWhenVerificationIsOff() throws Exception {
        repository = FakeRepository.start(false, true);
        AtomResult run = promote(JAR, "\"verify\": \"false\",");

        run.assertOk();
        assertEquals("false", run.field("verified"), "verify=false should not claim verification");
    }

    @Test
    @DisplayName("an empty path list is refused by name")
    void refusesAnEmptyPathList() throws Exception {
        repository = FakeRepository.start();
        AtomResult run = promote("", "");

        run.assertRefused();
        assertTrue(run.file("output.json").contains("paths is required"),
                "message: " + run.file("output.json"));
    }

    /** The input the smoke script wrote, with the case's extra fields in front of the closing brace. */
    private AtomResult promote(String paths, String extra) throws Exception {
        String url = repository.url();
        return atom.input("{\"sourceUrl\": \"" + url + "\", \"sourceRepository\": \"staging\","
                        + " \"sourceUsername\": \"ci\", \"sourceToken\": \"source-token\","
                        + " \"targetUrl\": \"" + url + "\", \"targetRepository\": \"release\","
                        + " \"targetUsername\": \"ci\", \"targetToken\": \"target-token\","
                        + " \"paths\": \"" + paths + "\","
                        + extra
                        + " \"timeoutSeconds\": \"10\"}")
                .run();
    }

    private static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /** A repository shaped like Artifactory/Nexus: GET, PUT, DELETE under /&lt;repo&gt;/&lt;path&gt;. */
    private static final class FakeRepository implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final List<Map<String, String>> log = Collections.synchronizedList(new ArrayList<>());
        private final boolean denyPut;
        private final boolean corruptTarget;

        private FakeRepository(HttpServer server, boolean denyPut, boolean corruptTarget) {
            this.server = server;
            this.denyPut = denyPut;
            this.corruptTarget = corruptTarget;
            store.put("staging/" + JAR, "jar-bytes\n");
            store.put("staging/" + POM, "<project/>\n");
            store.put("staging/com/acme/app/1.2.3/notes with spaces.txt", "spaced\n");
        }

        static FakeRepository start() throws IOException {
            return start(false, false);
        }

        static FakeRepository start(boolean denyPut, boolean corruptTarget) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeRepository repository = new FakeRepository(server, denyPut, corruptTarget);
            server.createContext("/", repository::handle);
            server.start();
            return repository;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** The stored text, or null when the path is not there. */
        String text(String key) {
            return store.get(key);
        }

        List<Map<String, String>> log() {
            synchronized (log) {
                return List.copyOf(log);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String key = exchange.getRequestURI().getPath().replaceFirst("^/", "");
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("method", exchange.getRequestMethod());
            entry.put("path", key);
            entry.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            log.add(entry);

            switch (exchange.getRequestMethod()) {
                case "GET" -> {
                    String body = store.get(key);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        byte[] bytes = (corruptTarget && key.startsWith("release/") ? body + "tampered" : body)
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                }
                case "PUT" -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (denyPut) {
                        byte[] refusal = "no deploy permission".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(403, refusal.length);
                        exchange.getResponseBody().write(refusal);
                    } else if (!key.startsWith("release/")) {
                        byte[] refusal = "unknown repository".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(400, refusal.length);
                        exchange.getResponseBody().write(refusal);
                    } else {
                        store.put(key, new String(body, StandardCharsets.UTF_8));
                        exchange.sendResponseHeaders(201, -1);
                    }
                }
                case "DELETE" -> exchange.sendResponseHeaders(store.remove(key) != null ? 204 : 404, -1);
                default -> exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
