package com.testing.aproxy.session;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Function;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * Keeps captured passwords encrypted in memory (AES-GCM, random key generated at startup).
 * <p>
 * This is defence in depth against accidental logging or serialisation of session objects;
 * it does not protect against someone who can dump the memory of the running JVM.
 */
@Component
public class CredentialVault {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public CredentialVault() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256, random);
        this.key = generator.generateKey();
    }

    /** Encrypts the password; the caller remains responsible for wiping its own array. */
    public SealedSecret seal(char[] password) {
        byte[] plain = toBytes(password);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new SealedSecret(iv, cipher.doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal credentials", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Gives the clear-text password to {@code use} and wipes it right after. */
    public <T> T withPassword(SealedSecret secret, Function<char[], T> use) {
        byte[] plain = null;
        char[] password = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, secret.iv()));
            plain = cipher.doFinal(secret.ciphertext());
            password = toChars(plain);
            return use.apply(password);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot open credentials", e);
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    @PreDestroy
    void destroy() {
        // Dropping the key makes every remaining ciphertext unreadable.
        key = null;
    }

    private SecretKey requireKey() {
        SecretKey k = key;
        if (k == null) {
            throw new IllegalStateException("Credential vault is closed");
        }
        return k;
    }

    private static byte[] toBytes(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }

    private static char[] toChars(byte[] bytes) {
        CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] chars = Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        Arrays.fill(buffer.array(), '\0');
        return chars;
    }

    /** Encrypted password. {@link #wipe()} zeroes it; {@code toString} never reveals content. */
    public static final class SealedSecret {

        private final byte[] iv;
        private final byte[] ciphertext;
        private volatile boolean wiped;

        SealedSecret(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        byte[] iv() {
            checkNotWiped();
            return iv;
        }

        byte[] ciphertext() {
            checkNotWiped();
            return ciphertext;
        }

        public boolean isWiped() {
            return wiped;
        }

        public void wipe() {
            wiped = true;
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }

        private void checkNotWiped() {
            if (wiped) {
                throw new IllegalStateException("Credentials have been wiped");
            }
        }

        @Override
        public String toString() {
            return "SealedSecret[***]";
        }
    }
}
