package com.testing.aproxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Set;

import org.junit.jupiter.api.Test;

class EnvironmentGuardTest {

    @Test
    void refusesEnvironmentOutsideAllowList() {
        assertThatThrownBy(() -> new EnvironmentGuard(props("PROD", Set.of("DEV", "UAT"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start");
    }

    @Test
    void acceptsAllowedEnvironmentCaseInsensitively() {
        assertThatCode(() -> new EnvironmentGuard(props("uat", Set.of("DEV", "UAT")))).doesNotThrowAnyException();
    }

    @Test
    void theAutosysTimeZoneIsEasternByDefault() {
        assertThat(props("DEV", Set.of("DEV")).zone()).isEqualTo(java.time.ZoneId.of("America/New_York"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AutosysProperties(
                URI.create("https://autosys.example.internal"), null, Set.of("DEV"), "DEV", null, null, null, null,
                null, null, null, null, null, null, "Mars/Olympus")).hasMessageContaining("autosys.time-zone");
    }

    private static AutosysProperties props(String env, Set<String> allowed) {
        return new AutosysProperties(URI.create("https://autosys.example.internal"), null, allowed, env,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
