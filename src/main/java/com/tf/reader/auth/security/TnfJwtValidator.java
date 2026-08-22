package com.tf.reader.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.auth.model.UserType;

/**
 * Checks a decoded token's expiry and required claims.
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

	private final Clock clock;

	public TnfJwtValidator(Clock clock) {
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

		// Types are checked on the RAW claims. Spring's getClaimAsString / getClaimAsStringList
		// coerce: a numeric roles claim becomes ["123"] and a numeric userId becomes "99", so a
		// structurally wrong token would be accepted and silently reinterpreted. Only we can sign,
		// so that is defence in depth rather than a hole - but a token whose shape we never issue
		// should be refused, not guessed at.
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
		// to an institution?" from institutionId alone. An INDIVIDUAL carrying one would therefore
		// pass requireSameInstitution for that institution - a subscriber reading a tenant's
		// resources - and an INSTITUTION user carrying none is an identity whose tenant we do not
		// know. Neither is a shape we ever issue; UserType says so, and this is where that stops
		// being a comment. Only we can sign, so this is defence in depth for the day the user
		// store behind the mapper is somebody else's collection.
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
