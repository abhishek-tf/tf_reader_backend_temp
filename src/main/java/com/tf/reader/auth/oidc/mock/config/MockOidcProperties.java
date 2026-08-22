package com.tf.reader.auth.oidc.mock.config;

import com.tf.reader.auth.oidc.mock.controller.MockOidcController;
import com.tf.reader.auth.oidc.mock.model.MockOidcUser;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The local mock provider's own configuration, bound from {@code mock-oidc.*}.
 *
 * <p><b>Separate from {@code tnf.auth.oidc.*} on purpose, and the separation is the point of the
 * whole exercise.</b> Those are the settings of the <em>relying party</em> - us, the client.
 * These are the settings of the <em>provider</em>, which in production is Azure AD B2C and is
 * emphatically not ours to configure. Keeping them in different namespaces is what makes it
 * obvious that deleting this entire package changes nothing about the client, and that the
 * migration to B2C is a matter of repointing six client properties at a real tenant.
 *
 * <p><b>{@link #enabled} defaults to false.</b> A mock identity provider that could be switched
 * on by forgetting to switch it off is a way to mint tokens for arbitrary users; it has to be an
 * explicit local decision. {@code SecurityArchitectureTest} asserts the default has not drifted.
 *
 * @param enabled      whether the mock exists at all. Local development and tests only
 * @param issuer       the {@code iss} the mock stamps into its ID tokens, and the base url of
 *                     its endpoints. Must equal {@code tnf.auth.oidc.issuer} or the client will
 *                     refuse every token it mints - which is the issuer check doing its job
 * @param port         an extra HTTP port to serve the mock on, so it has a genuinely different
 *                     origin from the application. Unset means "the application's own port",
 *                     which is what tests use so they need no fixed port
 * @param clientId     the client id the mock expects
 * @param clientSecret the client secret the mock expects. Defaults to the client's own
 *                     configured secret, so the local demo works without setting the same value
 *                     twice; tests set them differently to prove the check exists
 * @param redirectUri  the only redirect uri the mock will send a code to
 * @param codeTtl      how long an authorization code lives. Short: it is exchanged within
 *                     milliseconds of being issued, by a backend, over a direct connection
 * @param idTokenTtl   how long the ID token it mints lives
 * @param user         the single pre-populated user the mock authenticates
 */
@ConfigurationProperties(prefix = "mock-oidc")
public record MockOidcProperties(
		boolean enabled,
		String issuer,
		Integer port,
		String clientId,
		String clientSecret,
		String redirectUri,
		Duration codeTtl,
		Duration idTokenTtl,
		MockOidcUser user) {

	private static final Duration DEFAULT_CODE_TTL = Duration.ofMinutes(2);

	private static final Duration DEFAULT_ID_TOKEN_TTL = Duration.ofMinutes(5);

	public MockOidcProperties {
		codeTtl = (codeTtl != null) ? codeTtl : DEFAULT_CODE_TTL;
		idTokenTtl = (idTokenTtl != null) ? idTokenTtl : DEFAULT_ID_TOKEN_TTL;
		user = (user != null) ? user : MockOidcUser.defaultUser();
	}

	/** The discovery document's location, for the demo and the documentation. */
	public String discoveryUri() {
		return issuer + "/.well-known/openid-configuration";
	}

	public String authorizationUri() {
		return issuer + MockOidcController.AUTHORIZE_PATH;
	}

	public String tokenUri() {
		return issuer + MockOidcController.TOKEN_PATH;
	}

	public String jwkSetUri() {
		return issuer + MockOidcController.JWKS_PATH;
	}
}
