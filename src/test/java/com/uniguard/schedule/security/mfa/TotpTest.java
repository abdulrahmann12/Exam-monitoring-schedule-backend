package com.uniguard.schedule.security.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpTest {

	/** The RFC 6238 test secret "12345678901234567890" (ASCII), Base32-encoded. */
	private static final String RFC_SECRET = Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

	@Test
	void matchesTheOfficialRfc6238TestValues() {
		assertThat(RFC_SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
		// RFC 6238 appendix B (SHA-1), last 6 of the 8 digits.
		assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
		assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
		assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
		assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
	}

	@Test
	void acceptsOneStepOfClockDriftButNotMore() {
		Instant now = Instant.ofEpochSecond(1_700_000_000L);
		long step = Totp.stepAt(now);
		assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step), now, null)).isEqualTo(step);
		assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 1), now, null)).isEqualTo(step - 1);
		assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step + 1), now, null)).isEqualTo(step + 1);
		assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 2), now, null)).isEqualTo(-1);
		assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step + 2), now, null)).isEqualTo(-1);
	}

	@Test
	void aCodeCanBeUsedOnlyOnce() {
		Instant now = Instant.ofEpochSecond(1_700_000_000L);
		long step = Totp.stepAt(now);
		String code = Totp.codeAt(RFC_SECRET, step);
		assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEqualTo(-1);       // same step again
		assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).isEqualTo(step); // newer than the last one
	}

	@Test
	void rejectsAnythingThatIsNotSixDigits() {
		Instant now = Instant.now();
		assertThat(Totp.verify(RFC_SECRET, null, now, null)).isEqualTo(-1);
		assertThat(Totp.verify(RFC_SECRET, "", now, null)).isEqualTo(-1);
		assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEqualTo(-1);
		assertThat(Totp.verify(RFC_SECRET, "1234567", now, null)).isEqualTo(-1);
		assertThat(Totp.verify(RFC_SECRET, "12a456", now, null)).isEqualTo(-1);
		String spaced = Totp.codeAt(RFC_SECRET, Totp.stepAt(now));
		assertThat(Totp.verify(RFC_SECRET, spaced.substring(0, 3) + " " + spaced.substring(3), now, null)).isGreaterThan(0);
	}

	@Test
	void newSecretsAreRandom160BitBase32() {
		String a = Totp.newSecret();
		String b = Totp.newSecret();
		assertThat(a).hasSize(32).matches("[A-Z2-7]+").isNotEqualTo(b);
		assertThat(Totp.base32Decode(a)).hasSize(20);
		assertThatThrownBy(() -> Totp.base32Decode("not base32!")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void otpauthLinkIsEncodedForAuthenticatorApps() {
		String uri = Totp.otpauthUri("Uni-Guard Schedules", "sara@uni.edu", "JBSWY3DPEHPK3PXP");
		assertThat(uri).isEqualTo("otpauth://totp/Uni-Guard%20Schedules:sara%40uni.edu?secret=JBSWY3DPEHPK3PXP"
			+ "&issuer=Uni-Guard%20Schedules&algorithm=SHA1&digits=6&period=30");
	}

	@Test
	void storedSecretsAreEncryptedAndNeedTheSameKey() {
		SecretCipher cipher = new SecretCipher("dGVzdC1vbmx5LW1mYS1rZXktbm90LWZvci1wcm9kISE=");
		String first = cipher.encrypt("JBSWY3DPEHPK3PXP");
		String second = cipher.encrypt("JBSWY3DPEHPK3PXP");
		assertThat(first).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP").isNotEqualTo(second); // fresh IV each time
		assertThat(cipher.decrypt(first)).isEqualTo("JBSWY3DPEHPK3PXP");

		SecretCipher otherKey = new SecretCipher("bG9jYWwtb25seS1tZmEta2V5LW5vdC1mb3ItcHJvZCE=");
		assertThatThrownBy(() -> otherKey.decrypt(first)).isInstanceOf(IllegalStateException.class);
		String tampered = first.substring(0, first.length() - 4) + (first.endsWith("AAAA") ? "BBBB" : "AAAA");
		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void encryptionKeyMustBe32BytesOrAbsent() {
		assertThat(new SecretCipher("").isConfigured()).isFalse();
		assertThatThrownBy(() -> new SecretCipher("dG9vLXNob3J0")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretCipher("%%%not-base64%%%")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretCipher("").encrypt("x")).isInstanceOf(IllegalStateException.class);
	}
}
