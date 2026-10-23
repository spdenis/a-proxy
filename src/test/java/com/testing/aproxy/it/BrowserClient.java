package com.testing.aproxy.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal browser stand-in: never follows redirects and keeps a host-only cookie jar
 * (enough to check that cookies set via the proxy come back to it).
 */
final class BrowserClient {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    final Map<String, String> cookies = new LinkedHashMap<>();

    BrowserClient(int port) {
        this.base = "http://localhost:" + port;
    }

    String origin() {
        return base;
    }

    HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return send(request(path, headers).GET().build());
    }

    HttpResponse<String> delete(String path, String... headers) throws IOException, InterruptedException {
        return send(request(path, headers).DELETE().build());
    }

    HttpResponse<String> post(String path, String contentType, String body, String... headers)
            throws IOException, InterruptedException {
        return send(request(path, headers).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (String setCookie : setCookies(response)) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            cookies.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
        return response;
    }

    static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
