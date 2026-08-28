package com.tf.reader.auth.token;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.tf.reader.auth.dto.TokenResponse;

/**
 * Server-side store of one-time codes, keyed by an opaque value.
 *
 * <p><b>Why this exists.</b> The SAML ACS is reached by a browser redirect from the IdP, not a
 * fetch call the app can read a JSON body from. The access and refresh token are minted once, at
 * that redirect, and handed to the browser as a deep link carrying only this code - never the
 * tokens themselves, which would then sit in browser history and any proxy along the way. The app
 * redeems the code over its own connection, server to server from its perspective, at
 * {@code POST /auth/token}.
 *
 * <p>Mirrors {@code AuthTransactionStore}: in memory, single use enforced by removal rather than a
 * flag, short-lived because nobody is expected to sit on a deep link before the app opens it.
 */
@Component
public class AuthorizationCodeStore {

	/** Long enough for the OS to hand the deep link to the app, short enough to be useless later. */
	static final Duration LIFETIME = Duration.ofSeconds(60);

	static final int EVICT_ABOVE = 256;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final Map<String, IssuedCode> codes = new ConcurrentHashMap<>();
	private final Clock clock;

	public AuthorizationCodeStore(Clock clock) {
		this.clock = clock;
	}

	/** Issues a code for an already-minted token pair. */
	public String issue(TokenResponse tokens) {
		if (codes.size() >= EVICT_ABOVE) {
			evictExpired();
		}
		Instant now = clock.instant();
		String code = newCode();
		codes.put(code, new IssuedCode(tokens, now.plus(LIFETIME)));
		return code;
	}

	/**
	 * Redeems a code, removing it so it cannot be used twice.
	 *
	 * @return the token pair it was issued for, or empty if the code is unknown, already redeemed
	 *         or expired
	 */
	public Optional<TokenResponse> consume(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		IssuedCode issued = codes.remove(code);
		if (issued == null || issued.hasExpiredAt(clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(issued.tokens());
	}

	private void evictExpired() {
		Instant now = clock.instant();
		codes.values().removeIf(issued -> issued.hasExpiredAt(now));
	}

	private static String newCode() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return "authCode_" + ENCODER.encodeToString(bytes);
	}

	private record IssuedCode(TokenResponse tokens, Instant expiresAt) {

		boolean hasExpiredAt(Instant now) {
			return !now.isBefore(expiresAt);
		}
	}
}
