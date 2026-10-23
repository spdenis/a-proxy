package com.testing.aproxy.capture;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.proxy.HeaderRewriter;
import com.testing.aproxy.proxy.ProxyPaths;
import com.testing.aproxy.session.PendingTokenStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Capture mode ({@code autosys.capture.enabled=true}): writes every proxied AutoSys exchange
 * (static assets excluded) as one JSON file, with secrets masked by {@link Redactor}. Used to
 * learn the AutoSys UI's internal endpoints. Runs innermost, so it records AutoSys' own
 * request and response, before any rewriting of the response body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class CaptureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CaptureFilter.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter RUN_DIR = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final boolean enabled;
    private final ProxyPaths paths;
    private final Redactor redactor;
    private final int maxBodyBytes;
    private final Pattern exclude;
    private final Path runDir;
    private final AtomicInteger sequence = new AtomicInteger();

    public CaptureFilter(AutosysProperties properties, ProxyPaths paths) throws IOException {
        AutosysProperties.Capture capture = properties.capture();
        this.enabled = capture.enabled();
        this.paths = paths;
        var login = properties.webui().login();
        this.redactor = new Redactor(List.of(login.passwordField()));
        this.maxBodyBytes = capture.maxBodyBytes();
        this.exclude = Pattern.compile(capture.excludePathPattern());
        this.runDir = Path.of(capture.dir()).toAbsolutePath().normalize()
                .resolve(LocalDateTime.now().format(RUN_DIR));
        if (enabled) {
            Files.createDirectories(runDir);
            log.warn("CAPTURE MODE ON: recording AutoSys traffic to {} (secrets masked, but files contain job data; "
                    + "do not commit them; turn off with autosys.capture.enabled=false)", runDir);
        }
    }

    public Path runDir() {
        return runDir;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = ProxyPaths.pathOf(request);
        return !paths.isProxied(path) || exclude.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant start = Instant.now();
        CapturingRequest capturing = new CapturingRequest(request);
        Object requestBody = requestBody(capturing);
        ContentCachingResponseWrapper caching = new ContentCachingResponseWrapper(response);
        String error = null;
        try {
            chain.doFilter(capturing, caching);
        } catch (IOException | ServletException | RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            try {
                write(capturing, requestBody, caching, start, error);
            } catch (RuntimeException | IOException e) {
                log.warn("Could not write capture for {}: {}", ProxyPaths.pathOf(request), e.getMessage());
            }
            caching.copyBodyToResponse();
        }
    }

    private void write(HttpServletRequest request, @Nullable Object requestBody, ContentCachingResponseWrapper response,
            Instant start, @Nullable String error) throws IOException {
        int seq = sequence.incrementAndGet();
        String path = ProxyPaths.pathOf(request);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", request.getMethod());
        req.put("path", path);
        req.put("autosysPath", path.substring(paths.prefix().length()));
        req.put("query", request.getQueryString() == null ? null
                : redactor.params(queryParams(request.getQueryString())));
        req.put("contentType", request.getContentType());
        req.put("headers", redactor.headers(requestHeaders(request)));
        req.put("body", requestBody);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", response.getStatus());
        res.put("contentType", response.getContentType());
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            responseHeaders.put(name, List.copyOf(response.getHeaders(name)));
        }
        res.put("headers", redactor.headers(responseHeaders));
        res.put("bodyLength", response.getContentSize());
        res.put("body", body(response.getContentAsByteArray(), response.getContentType(), charsetOf(response)));

        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("seq", seq);
        capture.put("time", start.toString());
        capture.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());
        if (error != null) {
            capture.put("error", redactor.text(error));
        }
        capture.put("request", req);
        capture.put("response", res);

        Path file = runDir.resolve("%04d-%s-%s.json".formatted(seq, request.getMethod(), fileSafe(path)));
        JSON.writeValue(file.toFile(), capture);
        log.info("[capture] {} {} -> {} saved {}", request.getMethod(), path, response.getStatus(), file.getFileName());
    }

    private @Nullable Object requestBody(CapturingRequest request) throws IOException {
        String contentType = lower(request.getContentType());
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            // parsed by the container; Spring Cloud Gateway's FormFilter re-encodes it for forwarding
            return redactor.params(request.getParameterMap());
        }
        if (contentType.startsWith("multipart/")) {
            return "<multipart body not recorded>";
        }
        byte[] body = request.cachedBody();
        if (body.length == 0) {
            return null;
        }
        return body(body, request.getContentType(), charsetOf(request.getCharacterEncoding()));
    }

    /** JSON as a tree, text as a string, binary as a placeholder; secrets masked, size capped. */
    private @Nullable Object body(byte[] bytes, @Nullable String contentType, Charset charset) {
        if (bytes.length == 0) {
            return null;
        }
        String ct = lower(contentType);
        if (!isText(ct)) {
            return "<binary " + bytes.length + " bytes>";
        }
        if (ct.contains("json") && bytes.length <= maxBodyBytes) {
            try {
                JsonNode tree = JSON.readTree(bytes);
                if (tree != null) {
                    return redactor.json(tree);
                }
            } catch (RuntimeException e) {
                // not valid JSON after all; record as text
            }
        }
        String text = new String(bytes, 0, Math.min(bytes.length, maxBodyBytes), charset);
        String masked = redactor.text(text);
        return bytes.length > maxBodyBytes ? masked + "\n<truncated, " + bytes.length + " bytes total>" : masked;
    }

    static Map<String, String[]> queryParams(String query) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            values.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
        }
        Map<String, String[]> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, v.toArray(String[]::new)));
        return out;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static Map<String, List<String>> requestHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            List<String> values = Collections.list(request.getHeaders(name));
            if (HttpHeaders.COOKIE.equalsIgnoreCase(name)) {
                // as forwarded: the proxy's own cookies never reach AutoSys
                String forwarded = HeaderRewriter.stripOwnCookies(String.join("; ", values),
                        PendingTokenStore.OWN_COOKIE_PREFIX);
                if (forwarded == null) {
                    continue;
                }
                values = List.of(forwarded);
            }
            headers.put(name, values);
        }
        return headers;
    }

    private static boolean isText(String contentType) {
        return contentType.isEmpty() || contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("javascript") || contentType.contains("xml")
                || contentType.contains("x-www-form-urlencoded");
    }

    static String fileSafe(String path) {
        String safe = path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) {
            safe = "root";
        }
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String lower(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static Charset charsetOf(HttpServletResponse response) {
        return charsetOf(response.getCharacterEncoding());
    }

    private static Charset charsetOf(@Nullable String encoding) {
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Reads non-form bodies up front and replays them to the proxy; hides {@code Accept-Encoding}
     * so responses arrive uncompressed and can be recorded.
     */
    static final class CapturingRequest extends HttpServletRequestWrapper {

        private byte @Nullable [] body;

        CapturingRequest(HttpServletRequest request) {
            super(request);
        }

        byte[] cachedBody() throws IOException {
            if (body == null) {
                body = StreamUtils.copyToByteArray(super.getInputStream());
            }
            return body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            if (body == null) {
                return super.getInputStream();
            }
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            if (body == null) {
                return super.getReader();
            }
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    charsetOf(getCharacterEncoding())));
        }

        @Override
        public @Nullable String getHeader(String name) {
            return HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            return HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name) ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(n)).toList());
        }
    }
}
