package com.tf.reader.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.auth.model.UserType;
import com.tf.reader.common.security.TokenAudience;
import com.tf.reader.common.security.TokenClaims;

/**
 * Checks a decoded token's expiry, structural claims, issuer, audience, and token_use.
 *
 * <p>Runs after the signature has been verified - a token that failed cryptographic
 * verification never reaches here.
 *
 * <p><b>Why not Spring's {@code JwtTimestampValidator}.</b> Two reasons, both practical. It
 * reports expiry through a message string ("Jwt expired at …"), so telling an expired token
 * from a tampered one downstream would mean matching on Spring's wording and silently
 * mislabelling every refusal the day that wording changes. And it reads the system clock
 * directly, so expiry cannot be tested without waiting. This validator uses our own error
 * codes and the injected {@link Clock}.
 */
public class TnfJwtValidator implements OAuth2TokenValidator<Jwt> {

	/** Our error code for a token that was valid and is now past its expiry. */
	public static final String EXPIRED = "tnf_token_expired";

	/** Our error code for a token that is structurally unusable as an identity. */
	public static final String MISSING_CLAIMS = "tnf_token_missing_claims";

	/** The issuer we put into every token we mint. */
	private final String expectedIssuer;

	/** The single audience valid for app tokens. */
	private final String expectedAudience;

	private final Clock clock;

	/**
	 * @param expectedIssuer  the {@code iss} claim value from {@code tnf.auth.jwt} config
	 * @param expectedAudience the single audience string this validator accepts (e.g. {@link TokenAudience#APP})
	 * @param clock            injected clock so expiry tests don't require waiting
	 */
	public TnfJwtValidator(String expectedIssuer, String expectedAudience, Clock clock) {
		this.expectedIssuer = expectedIssuer;
		this.expectedAudience = expectedAudience;
		this.clock = clock;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Instant expiresAt = jwt.getExpiresAt();
		if (expiresAt == null) {
			return failure(MISSING_CLAIMS, "The token carries no expiry.");
		}
		// No clock skew allowance: we are the only issuer and the only verifier, so there are no
		// two clocks to disagree. A skew window here would just extend every token's life.
		if (!clock.instant().isBefore(expiresAt)) {
			return failure(EXPIRED, "The token expired at " + expiresAt + ".");
		}

		// ── Issuer ──
		// Compared against the configured value, not derived: a token signed with the same
		// secret but issued by something else (e.g. a stolen key) must be refused.
		Object issuerClaim = jwt.getClaims().get("iss");
		String issuer = issuerClaim instanceof String value ? value : null;
		if (!expectedIssuer.equals(issuer)) {
			return failure(MISSING_CLAIMS, "The token was not issued by this service.");
		}

		// ── Audience ──
		// Must be exactly the one audience this decoder is configured for. A token minted
		// for a different surface (e.g. admin) is rejected even if the signature is valid.
		List<String> audience = jwt.getAudience();
		if (audience == null || audience.size() != 1 || !expectedAudience.equals(audience.get(0))) {
			return failure(MISSING_CLAIMS, "The token audience does not match this service.");
		}

		// ── Token use ──
		// Distinguishes access tokens from any future refresh or other token types on the
		// same signing key. A token whose use is not 'access' is refused here rather than
		// reaching a controller and performing an action.
		Object tokenUse = jwt.getClaims().get(TokenClaims.TOKEN_USE);
		if (!TokenClaims.USE_ACCESS.equals(tokenUse)) {
			return failure(MISSING_CLAIMS, "The token is not an access token.");
		}

		// ── Identity claims ──
		// Types are checked on the RAW claims. Spring's getClaimAsString / getClaimAsStringList
		// coerce: a numeric roles claim becomes ["123"] and a numeric userId becomes "99", so a
		// structurally wrong token would be accepted and silently reinterpreted.
		if (!(jwt.getClaims().get("userId") instanceof String userId) || isBlank(userId)) {
			return failure(MISSING_CLAIMS, "The token carries no usable userId.");
		}
		if (!(jwt.getClaims().get("type") instanceof String type) || parseType(type) == null) {
			return failure(MISSING_CLAIMS, "The token carries no recognisable user type.");
		}
		// An absent or wrongly typed list would otherwise surface as a NullPointerException deep in
		// a service rather than as a 401 here, which is the wrong answer to a malformed token.
		if (!isListOfStrings(jwt.getClaims().get("roles"))) {
			return failure(MISSING_CLAIMS, "The token carries no usable roles.");
		}
		if (!isListOfStrings(jwt.getClaims().get("collections"))) {
			return failure(MISSING_CLAIMS, "The token carries no usable collections.");
		}
		// Optional, but when present it must be a string: it becomes the tenant boundary.
		Object institutionId = jwt.getClaims().get("institutionId");
		if (institutionId != null && !(institutionId instanceof String)) {
			return failure(MISSING_CLAIMS, "The token carries an unusable institutionId.");
		}
		// The type and the institution have to agree, because CurrentUser answers "do you belong
		// to an institution?" from institutionId alone.
		boolean hasInstitution = institutionId instanceof String value && !isBlank(value);
		if (hasInstitution != (parseType(type) == UserType.INSTITUTION)) {
			return failure(MISSING_CLAIMS,
					"The token's user type and institution do not agree.");
		}

		return OAuth2TokenValidatorResult.success();
	}

	/** @return the parsed type, or null if the claim is absent or not one of ours */
	static UserType parseType(String claim) {
		if (claim == null) {
			return null;
		}
		for (UserType candidate : UserType.values()) {
			if (candidate.name().equals(claim)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** A JSON array of strings, which is the only shape our encoder ever writes for these. */
	private static boolean isListOfStrings(Object claim) {
		return claim instanceof List<?> values
				&& values.stream().allMatch(String.class::isInstance);
	}

	private static OAuth2TokenValidatorResult failure(String code, String description) {
		return OAuth2TokenValidatorResult.failure(List.of(new OAuth2Error(code, description, null)));
	}
}
