package com.testing.aproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AutosysServersTest {

    static final URI A = URI.create("https://autosys-a:8443");
    static final URI B = URI.create("https://autosys-b:8443");
    static final URI C = URI.create("https://autosys-c:8443");

    @Test
    void startsOnTheFirstAndMovesOnOnlyWhenTheCurrentOneFails() {
        AutosysServers servers = servers(A, B, C);
        assertThat(servers.current()).isEqualTo(A);

        // a server that is not in use failing changes nothing
        assertThat(servers.failed(B, "test")).isEqualTo(A);

        assertThat(servers.failed(A, "test")).isEqualTo(B);
        // the same failure reported again by a request that started before the switch: no second move
        assertThat(servers.failed(A, "test")).isEqualTo(B);
        assertThat(servers.currentIndex()).isEqualTo(1);
    }

    @Test
    void afterTheLastComesTheFirstAgain() {
        AutosysServers servers = servers(A, B);
        servers.failed(A, "test");
        assertThat(servers.failed(B, "test")).isEqualTo(A);
    }

    @Test
    void aSingleServerStaysInUse() {
        AutosysServers servers = servers(A);
        assertThat(servers.failed(A, "test")).isEqualTo(A);
    }

    @Test
    void withoutBackendUrlsTheBaseUrlIsTheOnlyServer() {
        assertThat(new AutosysServers(properties(A, List.of())).all()).containsExactly(A);
    }

    private static AutosysServers servers(URI... urls) {
        return new AutosysServers(properties(A, List.of(urls)));
    }

    private static AutosysProperties properties(URI baseUrl, List<URI> backendUrls) {
        var values = new java.util.HashMap<String, Object>();
        values.put("autosys.base-url", baseUrl.toString());
        for (int i = 0; i < backendUrls.size(); i++) {
            values.put("autosys.backend-urls[" + i + "]", backendUrls.get(i).toString());
        }
        values.put("autosys.environment", "DEV");
        values.put("autosys.allowed-environments[0]", "DEV");
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("autosys", AutosysProperties.class).get();
    }
}
