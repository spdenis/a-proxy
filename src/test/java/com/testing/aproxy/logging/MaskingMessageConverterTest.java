package com.testing.aproxy.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingMessageConverterTest {

    @Test
    void masksPasswordsTokensAndCookies() {
        assertThat(MaskingMessageConverter.mask("body user=alice&j_password=s3cr3t&x=1"))
                .isEqualTo("body user=alice&j_password=***&x=1");
        assertThat(MaskingMessageConverter.mask("{\"password\": \"s3cr3t\"}")).doesNotContain("s3cr3t");
        assertThat(MaskingMessageConverter.mask("Authorization: Bearer abc")).isEqualTo("Authorization: ***");
        assertThat(MaskingMessageConverter.mask("Cookie: JSESSIONID=abc")).isEqualTo("Cookie: ***");
        assertThat(MaskingMessageConverter.mask("token asp_abcdefghijklmnopqrstuvwxyz0123"))
                .isEqualTo("token asp_***");
        assertThat(MaskingMessageConverter.mask("cookieNames=[JSESSIONID]")).isEqualTo("cookieNames=[JSESSIONID]");
    }
}
