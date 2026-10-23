package com.testing.aproxy.config;

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

    private static AutosysProperties props(String env, Set<String> allowed) {
        return new AutosysProperties(URI.create("https://autosys.example.internal"), allowed, env,
                null, null, null, null, null, null);
    }
}
