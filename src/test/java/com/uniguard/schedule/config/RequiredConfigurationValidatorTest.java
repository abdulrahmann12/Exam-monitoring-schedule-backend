package com.uniguard.schedule.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The server must refuse to start without its secrets — and that check must really be registered. */
class RequiredConfigurationValidatorTest {

	@Test
	void isRegisteredSoItRunsAtEveryStart() {
		// A wrong class name in META-INF/spring.factories (e.g. after renaming the package) would
		// silently switch the check off.
		assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
			.contains(RequiredConfigurationValidator.class.getName());
	}

	@Test
	void refusesToStartWithoutSecretsAndNamesEveryMissingOne() {
		MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://db/x");
		assertThatThrownBy(() -> new RequiredConfigurationValidator().postProcessEnvironment(environment, new SpringApplication()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DB_USERNAME is not set")
			.hasMessageContaining("DB_PASSWORD is not set")
			.hasMessageContaining("JWT_SECRET is not set")
			.hasMessageNotContaining("DB_URL");
	}

	@Test
	void refusesAShortJwtSecretAndAcceptsAComplete() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.datasource.url", "jdbc:mysql://db/x")
			.withProperty("spring.datasource.username", "u")
			.withProperty("spring.datasource.password", "p")
			.withProperty("app.jwt.secret", "too-short");
		assertThatThrownBy(() -> new RequiredConfigurationValidator().postProcessEnvironment(environment, new SpringApplication()))
			.hasMessageContaining("at least 32 characters");
		environment.setProperty("app.jwt.secret", "a-long-enough-secret-for-signing-tokens-123");
		new RequiredConfigurationValidator().postProcessEnvironment(environment, new SpringApplication());
	}
}
