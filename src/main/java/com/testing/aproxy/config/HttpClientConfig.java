package com.testing.aproxy.config;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Outbound HTTP client used by the gateway to reach AutoSys.
 * <p>
 * The proxy must be transparent, so the client never follows redirects, never manages
 * cookies (the browser owns them) and never decompresses bodies. TLS trust comes from the
 * configured truststore or the JVM default; there is intentionally no "trust all" option.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean(destroyMethod = "close")
    CloseableHttpClient autosysHttpClient(AutosysProperties properties) throws Exception {
        AutosysProperties.Http http = properties.http();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(http.maxConnections())
                .setMaxConnPerRoute(http.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(http.connectTimeout().toMillis(), TimeUnit.MILLISECONDS))
                        .setSocketTimeout(Timeout.of(http.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                        .build())
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext(http)))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setResponseTimeout(Timeout.of(http.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAutomaticRetries()
                .build();
    }

    /** Picked up by Spring Cloud Gateway MVC for its proxy RestClient. */
    @Bean
    ClientHttpRequestFactory autosysClientHttpRequestFactory(CloseableHttpClient autosysHttpClient) {
        return new HttpComponentsClientHttpRequestFactory(autosysHttpClient);
    }

    private static SSLContext sslContext(AutosysProperties.Http http) throws Exception {
        if (http.truststore() == null) {
            return SSLContexts.createSystemDefault();
        }
        KeyStore trustStore = KeyStore.getInstance(http.truststoreType());
        char[] password = http.truststorePassword() == null ? null : http.truststorePassword().toCharArray();
        try (InputStream in = http.truststore().getInputStream()) {
            trustStore.load(in, password);
        }
        return SSLContexts.custom().loadTrustMaterial(trustStore, null).build();
    }
}
