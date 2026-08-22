package com.tf.reader.auth.oidc.mock.service;

import com.tf.reader.auth.oidc.mock.config.MockOidcComponent;
import com.tf.reader.auth.oidc.mock.config.MockOidcProperties;
import com.tf.reader.auth.oidc.mock.security.MockOidcKeyService;
import com.tf.reader.auth.oidc.mock.store.MockAuthorizationCodeStore;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.StringUtils;

import com.nimbusds.jwt.JWTClaimsSet;
import com.tf.reader.auth.oidc.mock.store.MockAuthorizationCodeStore.IssuedCode;
import com.tf.reader.auth.oidc.mock.service.MockOidcAuthorizationService.MockOidcRequestException;

/**
 * The mock provider's token endpoint logic: redeem a code, mint a real signed ID token.
 *
 * <p><b>The ID token is genuinely signed, RS256, with a real key and a real {@code kid}.</b> An
 * unsigned or symmetrically-signed stand-in would make the relying party's most important check
 * - "is this token from who it says it is?" - untestable, and a mock that cannot exercise the
 * signature path is a mock that will let a signature bug reach production.
 *
 * <p><b>The access token is deliberately opaque</b> - random bytes, no structure. Real providers
 * differ on this and nothing in this application reads it; making it a JWT would invite somebody
 * to start parsing it, and an access token is an authorization grant, not an assertion about who
 * anybody is. The ID token is the only thing here that carries identity.
 */
@MockOidcComponent
public class MockOidcTokenService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MockOidcTokenService.class);

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final MockOidcProperties properties;
	private final MockAuthorizationCodeStore codes;
	private final MockOidcKeyService keys;
	private final Clock clock;

	public MockOidcTokenService(MockOidcProperties properties, MockAuthorizationCodeStore codes,
			MockOidcKeyService keys, Clock clock) {
		this.properties = properties;
		this.codes = codes;
		this.keys = keys;
		this.clock = clock;
	}

	/**
	 * Exchanges an authorization code for tokens, performing every check a real token endpoint
	 * performs.
	 *
	 * @throws MockOidcRequestException with the OAuth 2.0 error code the specification prescribes
	 */
	public Map<String, Object> exchange(String grantType, String code, String clientId,
			String clientSecret, String redirectUri) {

		if (!"authorization_code".equals(grantType)) {
			throw new MockOidcRequestException("unsupported_grant_type",
					"Only the authorization_code grant is supported.");
		}

		// The client authenticates BEFORE the code is looked at. Reversing these leaks whether a
		// given code exists to a caller who has not proved they are the client at all.
		requireClient(clientId, clientSecret);

		IssuedCode issued = codes.consume(code)
				.orElseThrow(() -> {
					// One error for unknown, expired and already-redeemed, as RFC 6749 §5.2
					// requires - distinguishing them tells an attacker whether a guess ever existed.
					log.warn("Mock OIDC token exchange refused: the authorization code is unknown, "
							+ "expired or has already been redeemed");
					return new MockOidcRequestException("invalid_grant",
							"The authorization code is not valid.");
				});

		if (!issued.clientId().equals(clientId)) {
			throw new MockOidcRequestException("invalid_grant",
					"This authorization code was not issued to this client.");
		}
		if (!issued.redirectUri().equals(redirectUri)) {
			// RFC 6749 §4.1.3. The redirect uri is repeated here precisely so a code delivered to
			// one uri cannot be redeemed towards another.
			throw new MockOidcRequestException("invalid_grant",
					"redirect_uri does not match the one the code was issued for.");
		}

		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(properties.idTokenTtl());
		String idToken = signIdToken(issued, issuedAt, expiresAt);

		log.info("Mock OIDC token exchange succeeded for {}", issued.user().sub());

		// LinkedHashMap so the JSON reads in the order the specification lists the fields, which
		// matters only to the human reading a demo, which is the entire audience for this mock.
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("access_token", randomOpaqueToken());
		response.put("token_type", "Bearer");
		response.put("expires_in", properties.idTokenTtl().toSeconds());
		response.put("id_token", idToken);
		response.put("scope", issued.scope());
		return response;
	}

	private void requireClient(String clientId, String clientSecret) {
		if (!StringUtils.hasText(clientId) || !clientId.equals(properties.clientId())) {
			throw new MockOidcRequestException("invalid_client", "Unknown client_id.");
		}
		// Compared to the mock's own configured secret. In the local setup that value defaults to
		// the client's, so the demo works without writing the same secret twice - but they are
		// separate properties, so a test can set them differently and prove this line exists.
		if (!StringUtils.hasText(clientSecret) || !clientSecret.equals(properties.clientSecret())) {
			log.warn("Mock OIDC token exchange refused: the client secret does not match");
			throw new MockOidcRequestException("invalid_client",
					"The client secret is not valid.");
		}
	}

	/**
	 * The ID token, with the claims OpenID Connect Core requires and the two our mapper reads.
	 *
	 * <p>{@code nonce} is copied from the authorization request the code came out of, which is
	 * what lets the relying party prove this token was minted for <em>that</em> request. A mock
	 * that dropped it would make the client's nonce check impossible to test, and a nonce check
	 * nobody tests is a nonce check that will be quietly deleted one day.
	 */
	private String signIdToken(IssuedCode issued, Instant issuedAt, Instant expiresAt) {
		try {
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
					.issuer(properties.issuer())
					.subject(issued.user().sub())
					.audience(issued.clientId())
					.issueTime(Date.from(issuedAt))
					.expirationTime(Date.from(expiresAt))
					.claim("email", issued.user().email())
					.claim("name", issued.user().name());

			if (StringUtils.hasText(issued.nonce())) {
				claims.claim("nonce", issued.nonce());
			}

			// The key service signs; the private key never leaves it.
			return keys.sign(claims.build());
		}
		catch (IllegalStateException failure) {
			throw new IllegalStateException("the mock provider could not sign an ID token", failure);
		}
	}

	private static String randomOpaqueToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}
}
