package com.testing.aproxy.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CredentialVaultTest {

    @Test
    void roundTripsAndZeroesThePasswordGivenToTheCaller() throws Exception {
        CredentialVault vault = new CredentialVault();
        var sealed = vault.seal("pässwörd-€".toCharArray());
        char[][] seen = new char[1][];

        String copy = vault.withPassword(sealed, pw -> {
            seen[0] = pw;
            return new String(pw);
        });

        assertThat(copy).isEqualTo("pässwörd-€");
        assertThat(new String(seen[0])).isEqualTo("\0".repeat(seen[0].length));
        assertThat(sealed.toString()).doesNotContain("pässwörd");
    }

    @Test
    void wipedOrClosedSecretsCannotBeOpened() throws Exception {
        CredentialVault vault = new CredentialVault();
        var sealed = vault.seal("pw".toCharArray());
        sealed.wipe();
        assertThatThrownBy(() -> vault.withPassword(sealed, String::new)).isInstanceOf(IllegalStateException.class);

        var other = vault.seal("pw".toCharArray());
        vault.destroy();
        assertThatThrownBy(() -> vault.withPassword(other, String::new)).isInstanceOf(IllegalStateException.class);
    }
}
