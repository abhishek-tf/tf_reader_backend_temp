package com.tf.reader.auth.oidc.mock.store;

import com.tf.reader.auth.oidc.mock.config.MockOidcComponent;
import com.tf.reader.auth.oidc.mock.config.MockOidcProperties;
import com.tf.reader.auth.oidc.mock.model.MockOidcUser;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * The mock provider's authorization codes.
 *
 * <p>A code is the whole reason the authorization-code flow is worth implementing rather than
 * faking: it is the thing that travels through the browser <em>instead of</em> a token. On its
 * own it is useless - redeeming it needs the client secret, over a back channel, once, within
 * two minutes, against the same redirect uri it was issued for. Everything in {@link IssuedCode}
 * exists so the token endpoint can enforce one of those.
 *
 * <p><b>Single use is enforced by removal, not by a flag.</b> {@code consume} takes the entry out
 * of the map, so two simultaneous exchanges cannot both find it - a boolean would need a lock to
 * mean anything, and the version without one passes every test and fails under load.
 */
@MockOidcComponent
public class MockAuthorizationCodeStore {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final Map<String, IssuedCode> codes = new ConcurrentHashMap<>();
	private final Clock clock;
	private final MockOidcProperties properties;

	public MockAuthorizationCodeStore(Clock clock, MockOidcProperties properties) {
		this.clock = clock;
		this.properties = properties;
	}

	/** Issues a code for an authenticated user and the request it belongs to. */
	public IssuedCode issue(String clientId, String redirectUri, String scope, String nonce,
			MockOidcUser user) {
		Instant now = clock.instant();
		IssuedCode code = new IssuedCode(
				randomCode(), clientId, redirectUri, scope, nonce, user,
				now, now.plus(properties.codeTtl()));

		codes.put(code.code(), code);
		evictExpired();
		return code;
	}

	/**
	 * Redeems a code, removing it.
	 *
	 * @return the code, or empty if it is unknown, already redeemed or expired - three cases the
	 *         token endpoint must answer identically, because telling them apart tells an attacker
	 *         whether a code they guessed ever existed
	 */
	public Optional<IssuedCode> consume(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		IssuedCode issued = codes.remove(code);
		if (issued == null || issued.hasExpiredAt(clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(issued);
	}

	/** Codes currently outstanding. Package-private: for tests, not for callers. */
	int outstanding() {
		return codes.size();
	}

	private void evictExpired() {
		Instant now = clock.instant();
		codes.values().removeIf(issued -> issued.hasExpiredAt(now));
	}

	private static String randomCode() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	/**
	 * One issued authorization code and everything the token endpoint has to check it against.
	 *
	 * @param clientId    who it was issued to. A code issued to one client must not be redeemable
	 *                    by another, even one holding a valid secret of its own
	 * @param redirectUri where the code was sent. RFC 6749 §4.1.3 requires the exchange to repeat
	 *                    it, so a code intercepted at one redirect cannot be redeemed towards
	 *                    another
	 * @param scope       what was granted, echoed back in the token response
	 * @param nonce       from the authorization request, and stamped into the ID token. This is
	 *                    the field that binds the eventual token to this one request
	 * @param user        who authenticated
	 */
	public record IssuedCode(
			String code,
			String clientId,
			String redirectUri,
			String scope,
			String nonce,
			MockOidcUser user,
			Instant issuedAt,
			Instant expiresAt) {

		public boolean hasExpiredAt(Instant now) {
			return !now.isBefore(expiresAt);
		}
	}
}
