package com.tf.reader.auth.oidc.validation;

import java.time.Clock;
import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.tf.reader.auth.oidc.client.OidcProperties;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Verifies an ID token the identity provider issued: <b>signature, issuer, audience, expiry</b>.
 *
 * <p><b>Why this class is in {@code auth.security} rather than in {@code auth.oidc}.</b>
 * {@code SecurityArchitectureTest} asserts that nothing outside {@code auth.token} and
 * {@code auth.security} names a {@code JwtDecoder}: one place decides what a valid token is,
 * because a second parser is a second set of rules and they will diverge. That rule is exactly
 * about the thing this class does, so it belongs here, beside {@link com.tf.reader.auth.security.TnfJwtValidator} - which
 * does the same job for the tokens we mint ourselves. The OIDC-flow-level checks that depend on
 * the sign-in in flight (the nonce, the claims we require) live in
 * {@code auth.oidc.OidcIdTokenValidator} and never touch a decoder.
 *
 * <p><b>What each check is for, and what breaks without it:</b>
 * <ul>
 * <li><b>Signature</b>, against the provider's published JWKS, fetched over HTTPS from
 * {@code jwk-set-uri} and cached by Nimbus. Pinned to RS256. Without it an ID token is just a
 * base64 string anybody can write, naming any user in the directory. {@code alg: none} is not
 * "trivially valid" here, it is unreadable.</li>
 * <li><b>Issuer</b>, compared to the configured {@code iss}. Not optional and not derived: see
 * the note below.</li>
 * <li><b>Audience</b>, which must contain our client id. A token the provider minted for a
 * <em>different</em> application in the same tenant is signed by the same key and would
 * otherwise verify perfectly.</li>
 * <li><b>Expiry</b>, with the injected {@link Clock}, per the project rule that no expiry is
 * ever decided by {@code Instant.now()}.</li>
 * </ul>
 *
 * <p><b>The issuer is validated explicitly, and this is the subtle one.</b> Spring Security's
 * own {@code OidcIdTokenValidator} compares {@code iss} only when the client registration was
 * built from an {@code issuer-uri}, and silently skips the comparison otherwise. For Azure AD
 * B2C there is no usable {@code issuer-uri}: the {@code issuer} in its metadata carries the
 * directory <i>guid</i>, while the metadata is served from a url carrying the tenant <i>name</i>
 * and the policy, and Spring's discovery requires those to be equal. So a working B2C setup
 * configures endpoints explicitly, has no issuer uri, and would accept a token from any issuer
 * whose signature happened to verify. <b>A discovery url is not a token issuer.</b> Here the
 * expected {@code iss} is its own configuration value and a {@link JwtIssuerValidator} enforces
 * it, for the mock and for B2C alike.
 */
@Component
public class OidcIdTokenDecoder {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(OidcIdTokenDecoder.class);

	private final JwtDecoder decoder;
	private final String expectedIssuer;
	private final String expectedAudience;

	public OidcIdTokenDecoder(OidcProperties properties, Clock clock) {
		this.expectedIssuer = properties.issuer();
		this.expectedAudience = properties.clientId();

		NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
				// One algorithm, stated. Left open, the decoder accepts whatever the JWKS offers,
				// and "whatever the token asks for" is how algorithm-confusion bugs start.
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.build();

		// The injected Clock, not the system one, so "an expired ID token is refused" is a test
		// rather than an hour's wait. Nimbus allows 60 seconds of skew by default and that is left
		// alone: unlike our own tokens, where we are the only issuer and the only verifier and
		// TnfJwtValidator allows none, here there genuinely are two clocks.
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setClock(clock);

		nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
				new JwtIssuerValidator(this.expectedIssuer),
				timestamps,
				audienceValidator(this.expectedAudience))));

		this.decoder = nimbus;
	}

	/**
	 * @param idToken the raw ID token as it came back from the token endpoint
	 * @return the verified token, whose claims are now safe to read
	 * @throws ApiException 401 if any check fails. One code for all of them: which check failed
	 *                      is useful to somebody probing our configuration and to nobody else
	 */
	public Jwt verify(String idToken) {
		try {
			Jwt verified = this.decoder.decode(idToken);
			log.debug("OIDC ID token verified: signature, issuer, audience and expiry all passed");
			return verified;
		}
		catch (JwtException rejected) {
			// The reason is logged, never returned, and the token itself is never logged - it is a
			// credential, and it names a real person.
			log.warn("OIDC ID token rejected: {}", rejected.getMessage());
			throw new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
					"The identity provider's token could not be validated.");
		}
	}

	/** The expected {@code iss}, for anything that wants to report the configuration. */
	public String expectedIssuer() {
		return this.expectedIssuer;
	}

	/**
	 * {@code aud} must contain our client id.
	 *
	 * <p>Written out rather than taken from Spring's OIDC validator because that one is reached
	 * only through a {@code ClientRegistration}, which this flow deliberately does not use.
	 */
	private static OAuth2TokenValidator<Jwt> audienceValidator(String clientId) {
		return token -> {
			List<String> audience = token.getAudience();
			if (audience != null && audience.contains(clientId)) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
					"The " + JwtClaimNames.AUD + " claim does not contain this application.", null));
		};
	}
}
