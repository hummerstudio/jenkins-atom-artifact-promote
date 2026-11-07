package com.jenkinschina.atom.artifactpromote;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copies an artifact from one repository to another and proves it arrived.
 *
 * <p>The artifact-promotion plugin does this inside Artifactory with its own promotion API; the transferable step is
 * "this file moves from staging to release, unchanged". Doing it over plain HTTP means it works against Artifactory,
 * Nexus and anything else that speaks GET/PUT, and the copy is verified by reading the target back and comparing
 * digests rather than trusting a 201.
 */
public class ArtifactPromoteAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String sourceUrl = context.getParam("sourceUrl", "").trim();
        String sourceRepo = context.getParam("sourceRepository", "").trim();
        String targetUrl = context.getParam("targetUrl", "").trim();
        String targetRepo = context.getParam("targetRepository", "").trim();
        String paths = context.getParam("paths", "");
        String targetPath = context.getParam("targetPath", "").trim();
        String sourceUser = context.getParam("sourceUsername", "").trim();
        String sourceToken = secret(context, "sourceToken", "sourcePassword");
        String targetUser = context.getParam("targetUsername", "").trim();
        String targetToken = secret(context, "targetToken", "targetPassword");
        boolean deleteSource = context.getBooleanParam("deleteSource", false);
        boolean verify = context.getBooleanParam("verify", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 600));

        if (sourceUrl.isEmpty() || sourceRepo.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "sourceUrl and sourceRepository are required");
        }
        if (targetUrl.isEmpty() || targetRepo.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "targetUrl and targetRepository are required");
        }
        List<String> wanted = new ArrayList<>();
        for (String part : paths.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                wanted.add(trimmed);
            }
        }
        if (wanted.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "paths is required (the artifact path inside the repository)");
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        String sourceAuth = authorization(sourceUser, sourceToken);
        String targetAuth = authorization(targetUser, targetToken);
        String sourceRoot = join(sourceUrl, sourceRepo);
        String targetRoot = join(targetUrl, targetRepo);

        long bytes = 0;
        List<String> promoted = new ArrayList<>();
        for (String path : wanted) {
            String targetRelative = targetPath.isEmpty() ? path : joinPath(targetPath, fileName(path));
            String from = sourceRoot + "/" + encodePath(path);
            Reply fetched = send(client, "GET", from, null, sourceAuth, timeoutSeconds);
            if (!fetched.ok()) {
                return AtomResult.error(fetched.type(), 40004,
                        "cannot read " + path + " from " + sourceRepo + ": " + fetched.describe()
                                + (fetched.status == 404 ? " (check the path and the repository)" : ""));
            }

            String to = targetRoot + "/" + encodePath(targetRelative);
            Reply put = send(client, "PUT", to, fetched.bytes, targetAuth, timeoutSeconds);
            if (!put.ok()) {
                ErrorType type = put.status == 401 || put.status == 403 ? ErrorType.USER : ErrorType.THIRD_PART;
                return AtomResult.error(type, 40005, "publishing " + targetRelative + " failed: " + put.describe()
                        + (put.status == 401 || put.status == 403 ? " (the token needs deploy rights on " + targetRepo + ")" : ""));
            }

            if (verify) {
                Reply readBack = send(client, "GET", to, null, targetAuth, timeoutSeconds);
                if (!readBack.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40006,
                            targetRelative + " was accepted but cannot be read back: " + readBack.describe());
                }
                String wantedDigest = sha256(fetched.bytes);
                String gotDigest = sha256(readBack.bytes);
                if (!wantedDigest.equals(gotDigest)) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40007,
                            targetRelative + " differs from the source: " + wantedDigest.substring(0, 12)
                                    + " vs " + gotDigest.substring(0, 12));
                }
            }

            if (deleteSource) {
                Reply deleted = send(client, "DELETE", from, null, sourceAuth, timeoutSeconds);
                if (!deleted.ok() && deleted.status != 404) {
                    context.log("[promote] warning: could not remove the source " + path + ": " + deleted.describe());
                }
            }

            bytes += fetched.bytes.length;
            promoted.add(targetRelative);
            context.log("[promote] " + path + " -> " + targetRepo + "/" + targetRelative
                    + " (" + fetched.bytes.length + " bytes" + (verify ? ", verified" : "") + ")");
        }

        return AtomResult.success()
                .data("promoted", String.valueOf(promoted.size()))
                .data("bytes", String.valueOf(bytes))
                .data("targetRepository", targetRepo)
                .data("artifacts", String.join(",", promoted))
                .data("verified", verify ? "true" : "false");
    }

    private static String secret(AtomContext context, String primary, String fallback) {
        String value = context.getSecret(primary);
        if (value == null || value.isBlank()) {
            value = context.getSecret(fallback);
        }
        if (value == null || value.isBlank()) {
            value = context.getParam(primary, context.getParam(fallback, ""));
        }
        return value;
    }

    private static String authorization(String user, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String pair = (user == null ? "" : user) + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply send(HttpClient client, String method, String url, byte[] body, String auth,
                              int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "jenkins-atom-artifact-promote")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.header("Content-Type", "application/octet-stream");
        }
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private static String join(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private static String joinPath(String directory, String name) {
        return directory.replaceAll("/+$", "") + "/" + name;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String encodePath(String value) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : value.split("/")) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private record Reply(int status, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Reply reply
                    && status == reply.status
                    && Arrays.equals(bytes, reply.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(status) + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + ", bytes=" + Arrays.toString(bytes) + "]";
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        ErrorType type() {
            return switch (status) {
                case 400, 401, 403, 404, 405, 409 -> ErrorType.USER;
                default -> ErrorType.THIRD_PART;
            };
        }

        String describe() {
            if (status == 401 || status == 403) {
                return "HTTP " + status + " (credentials rejected)";
            }
            return "HTTP " + status;
        }
    }
}
