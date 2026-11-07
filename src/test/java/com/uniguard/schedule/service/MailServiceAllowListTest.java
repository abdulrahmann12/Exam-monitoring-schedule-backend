package com.uniguard.schedule.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The test safety net: with an allow-list, nobody outside it can be emailed by accident. */
class MailServiceAllowListTest {

	private static MailService mail(String allowed) {
		// Unroutable API address: if the guard ever let a message through, the test would fail on the network instead.
		return new MailService("test-key", "sender@test.local", "Test", "http://127.0.0.1:9/never", allowed);
	}

	@Test
	void addressesOutsideTheListAreNeverSentAndNotRetried() {
		MailService service = mail(" BoudyBackup@gmail.com , other@test.local ");
		assertThatThrownBy(() -> service.sendHtml("sara@example.edu", "Schedule", "<p>hi</p>"))
			.isInstanceOfSatisfying(MailDeliveryException.class, ex -> {
				assertThat(ex.retryable()).isFalse();
				assertThat(ex.getMessage()).contains("sara@example.edu").contains("test mode");
			});
	}

	@Test
	void anAllowedAddressGoesThroughTheGuard() {
		MailService service = mail("boudybackup@gmail.com");
		// Passes the guard (any case) and only then fails on the unroutable test address.
		assertThatThrownBy(() -> service.sendHtml("BoudyBackup@Gmail.com", "Schedule", "<p>hi</p>"))
			.isInstanceOf(MailDeliveryException.class)
			.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("test mode"));
	}

	@Test
	void anEmptyListMeansNormalSending() {
		assertThat(mail("").isConfigured()).isTrue();
	}
}
