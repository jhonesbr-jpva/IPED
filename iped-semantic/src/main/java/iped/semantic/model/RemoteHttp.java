package iped.semantic.model;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The HTTP side of the remote model sources (contracts/remote-model-protocol.md), with the JDK's own
 * client. Every request goes to the declared destination and only to it (SC-016): redirects are
 * not followed. The credential goes in {@code Authorization: Bearer} and nowhere else.
 *
 * <p>
 * Failures follow the contract: connection refused, timeout and 5xx are retried with growing waits
 * up to {@code remoteMaxRetries}, then reported as {@link EmbeddingException#remoteUnavailable};
 * 429 waits for {@code Retry-After} when given; 401/403 and other 4xx are configuration errors,
 * never retried. Error messages never carry the response body, which may echo evidence content.
 * </p>
 *
 * <p>
 * Requests run in {@code AccessController.doPrivileged}: IPED's security policy refuses network
 * access to any call stack that passes through its HTML viewers, and a question can start from a
 * viewer ("more like this" on a selected passage). The privilege is this module's, not the caller's.
 * </p>
 */
final class RemoteHttp {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteHttp.class);

    static final ObjectMapper JSON = new ObjectMapper();

    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long MAX_RETRY_AFTER_MS = 120_000;

    private final URI base;
    private final String destination;
    private final CredentialResolver.Credential credential;
    private final Duration timeout;
    private final int maxRetries;
    private final long baseDelayMillis;
    private final HttpClient client;

    RemoteHttp(URI base, CredentialResolver.Credential credential, int timeoutSeconds, int maxRetries,
            long baseDelayMillis) throws EmbeddingException {
        this.base = base;
        this.destination = RemoteEmbedders.safeDestination(base.toString());
        this.credential = credential;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxRetries = Math.max(0, maxRetries);
        this.baseDelayMillis = Math.max(0, baseDelayMillis);
        try {
            this.client = privileged(() -> HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build());
        } catch (IOException | InterruptedException e) {
            throw new EmbeddingException("cannot create the HTTP client: " + e, e, true);
        }
    }

    /** The destination as it may be shown: no user info, query or fragment. */
    String destination() {
        return destination;
    }

    /**
     * Parses and checks a configured endpoint: an absolute http(s) URL with a host and no user info
     * (a credential in the URL would end up in the configuration and in the record); plain HTTP only
     * for a loopback host or with {@code remoteAllowPlainHttp = true}.
     */
    static URI checkEndpoint(String endpoint, boolean allowPlainHttp) throws EmbeddingException {
        URI u;
        try {
            u = new URI(endpoint == null ? "" : endpoint.trim());
        } catch (URISyntaxException e) {
            throw new EmbeddingException("the remote endpoint is not a valid URL", null, true);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || u.getHost() == null) {
            throw new EmbeddingException("the remote endpoint must be an absolute https URL, not '"
                    + RemoteEmbedders.safeDestination(endpoint) + "'", null, true);
        }
        if (u.getRawUserInfo() != null) {
            throw new EmbeddingException("the remote endpoint carries user information; the credential belongs in the "
                    + "environment variable or file named in the configuration, never in the URL", null, true);
        }
        if (scheme.equals("http") && !allowPlainHttp && !isLoopback(u.getHost())) {
            throw new EmbeddingException("plain HTTP to " + RemoteEmbedders.safeDestination(endpoint)
                    + " is refused: use HTTPS, a loopback address, or set remoteAllowPlainHttp = true", null, true);
        }
        return u;
    }

    /** A literal loopback address or {@code localhost}; no name is resolved to decide it. */
    static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    JsonNode post(String path, JsonNode body) throws EmbeddingException {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new EmbeddingException("cannot encode the request: " + e.getMessage(), e);
        }
        HttpRequest.Builder rb = request(path).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        return send(rb, path, false);
    }

    /** GET; {@code null} when the destination answers 404 (an optional endpoint). */
    JsonNode getOptional(String path) throws EmbeddingException {
        return send(request(path).GET(), path, true);
    }

    private HttpRequest.Builder request(String path) {
        String b = base.toString();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(b + path)).timeout(timeout)
                .header("Accept", "application/json");
        if (credential.isPresent()) {
            rb.header("Authorization", "Bearer " + credential.value());
        }
        return rb;
    }

    private JsonNode send(HttpRequest.Builder rb, String path, boolean optional) throws EmbeddingException {
        HttpRequest request = rb.build();
        String last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long wait = backoff(attempt);
            try {
                HttpResponse<String> r = privileged(
                        () -> client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                int status = r.statusCode();
                if (status == 401 || status == 403) {
                    throw new EmbeddingException("credential refused by the destination " + destination + " (HTTP "
                            + status + ")", null, true);
                }
                if (status == 404 && optional) {
                    return null;
                }
                if (status == 429) {
                    last = "HTTP 429 (rate limit)";
                    wait = retryAfter(r).orElse(wait);
                } else if (status >= 500) {
                    last = "HTTP " + status;
                } else if (status >= 300 && status < 400) {
                    throw new EmbeddingException("the destination " + destination + " answered with a redirect (HTTP "
                            + status + "); redirects are not followed, requests go only to the declared destination",
                            null, true);
                } else if (status >= 400) {
                    throw new EmbeddingException("the destination " + destination + " refused the request to " + path
                            + " (HTTP " + status + ")", null, true);
                } else {
                    try {
                        return JSON.readTree(r.body());
                    } catch (IOException e) {
                        throw new EmbeddingException("the destination " + destination + " answered with something that is "
                                + "not JSON", e, true);
                    }
                }
            } catch (IOException e) {
                last = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("interrupted while waiting for " + destination, e);
            }
            if (attempt < maxRetries) {
                LOGGER.warn("Remote model at {} failed ({}); retry {} of {} in {} ms", destination, last, attempt + 1,
                        maxRetries, wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingException("interrupted while waiting for " + destination, e);
                }
            }
        }
        throw EmbeddingException.remoteUnavailable(
                "remote model at " + destination + " did not answer after " + (maxRetries + 1) + " attempts: " + last,
                null);
    }

    private long backoff(int attempt) {
        long w = baseDelayMillis * (1L << Math.min(attempt, 20));
        return Math.min(w, MAX_BACKOFF_MS);
    }

    /** {@code Retry-After} in seconds (the HTTP-date form is not honoured: the backoff applies). */
    private static Optional<Long> retryAfter(HttpResponse<?> r) {
        Optional<String> v = r.headers().firstValue("Retry-After");
        if (v.isEmpty()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(v.get().trim());
            return Optional.of(Math.min(MAX_RETRY_AFTER_MS, Math.max(0, seconds) * 1000));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("removal")
    private static <T> T privileged(PrivilegedExceptionAction<T> action) throws IOException, InterruptedException {
        try {
            return AccessController.doPrivileged(action);
        } catch (PrivilegedActionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) {
                throw (IOException) c;
            }
            if (c instanceof InterruptedException) {
                throw (InterruptedException) c;
            }
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            throw new IOException(c);
        }
    }

    @Override
    public String toString() {
        return "RemoteHttp[" + destination + ", " + credential + "]";
    }
}
