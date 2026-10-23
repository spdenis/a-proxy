package com.testing.aproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.testing.aproxy.proxy.LoginClassifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The shipped application.yml leaves login rules unset; they must not bind as empty-string rules. */
@SpringBootTest
class DefaultConfigTest {

    @Autowired
    AutosysProperties properties;

    @Autowired
    LoginClassifier classifier;

    @Test
    void emptyYamlValuesMeanNoRule() {
        var login = properties.webui().login();
        assertThat(login.success().cookie()).isNull();
        assertThat(login.success().locationPattern()).isNull();
        assertThat(login.failureMarker()).isNull();
        assertThat(classifier.isConfigured()).isFalse();
    }
}
