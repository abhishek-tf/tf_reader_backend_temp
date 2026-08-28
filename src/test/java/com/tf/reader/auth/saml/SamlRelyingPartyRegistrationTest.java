package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.saml2.provider.service.registration.IterableRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

/**
 * Guards the two properties of the SAML setup that are easy to break by accident and expensive
 * to notice: that there is exactly ONE registration for every institution, and that signature
 * verification is actually configured.
 *
 * <p>Reads the real {@code application.yml}, so it fails if that file drifts.
 */
class SamlRelyingPartyRegistrationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
					ServletWebSecurityAutoConfiguration.class, Saml2RelyingPartyAutoConfiguration.class))
			// Loads the real application.yml, so this fails if that file drifts.
			.withInitializer(new ConfigDataApplicationContextInitializer())
			// application.yml defaults spring.profiles.active to local, and a developer's own
			// gitignored application-local.yml (never committed - see CLAUDE.md) points the mock
			// registration at their own machine instead of samlmock.dev. Suppressing that profile
			// here is what makes this test see the same config on every machine and in CI.
			.withPropertyValues("spring.profiles.active=");

	@Test
	void thereIsExactlyOneRegistrationAndEveryInstitutionSharesIt() {
		runner.run(context -> {
			List<RelyingPartyRegistration> registrations = registrationsIn(context.getBean(
					RelyingPartyRegistrationRepository.class));

			assertThat(registrations)
					.describedAs("one SAML integration serves every institution; a second "
							+ "registration means the architecture has regressed to one IdP per institution")
					.hasSize(1);
			assertThat(registrations.get(0).getRegistrationId()).isEqualTo("tf-reader");
		});
	}

	@Test
	void noRegistrationIsNamedAfterAnInstitution() {
		runner.run(context -> {
			List<RelyingPartyRegistration> registrations = registrationsIn(context.getBean(
					RelyingPartyRegistrationRepository.class));

			assertThat(registrations).allSatisfy(registration ->
					assertThat(registration.getRegistrationId()).doesNotContain("inst_"));
		});
	}

	@Test
	void pointsAtTheMockIdpWithOurAudienceAndAcsPrefilled() {
		runner.run(context -> {
			RelyingPartyRegistration registration = only(context);

			assertThat(registration.getEntityId()).isEqualTo("tf-reader-sp");
			assertThat(registration.getAssertingPartyMetadata().getEntityId()).isEqualTo("saml-mock");
			assertThat(registration.getAssertingPartyMetadata().getSingleSignOnServiceLocation())
					.startsWith("https://samlmock.dev/idp")
					.contains("aud=tf-reader-sp")
					.contains("acs_url=");
		});
	}

	@Test
	void assertionSignaturesAreVerifiedAgainstTheIdpCertificate() {
		// The single most important line of the configuration. Without a verification
		// credential the SP would accept an assertion minted by anybody.
		runner.run(context -> assertThat(only(context).getAssertingPartyMetadata()
				.getVerificationX509Credentials())
				.describedAs("SAML signature verification must never be disabled")
				.isNotEmpty());
	}

	@Test
	void weDoNotSignTheAuthnRequest() {
		// Deliberate: samlmock.dev never verifies it, and signing would mean committing an SP
		// private key. This is about the outbound request only - response validation above
		// stays fully enabled.
		runner.run(context -> assertThat(only(context).getAssertingPartyMetadata()
				.getWantAuthnRequestsSigned()).isFalse());
	}

	private RelyingPartyRegistration only(org.springframework.context.ApplicationContext context) {
		return registrationsIn(context.getBean(RelyingPartyRegistrationRepository.class)).get(0);
	}

	private List<RelyingPartyRegistration> registrationsIn(RelyingPartyRegistrationRepository repository) {
		List<RelyingPartyRegistration> found = new ArrayList<>();
		((IterableRelyingPartyRegistrationRepository) repository).forEach(found::add);
		return found;
	}
}
