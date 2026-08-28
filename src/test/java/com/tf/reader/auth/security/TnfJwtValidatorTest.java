package com.tf.reader.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Expiry and required claims, with our own error codes so the entry point can tell an expired
 * token from a broken one without reading Spring's message text.
 */
class TnfJwtValidatorTest {

	private static final Instant NOW = Instant.parse("2026-08-13T14:42:00Z");

	private final TnfJwtValidator validator = new TnfJwtValidator(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void acceptsAWellFormedUnexpiredToken() {
		assertThat(validator.validate(jwt(NOW.plusSeconds(60), claims())).hasErrors()).isFalse();
	}

	@Test
	void rejectsAnExpiredTokenWithTheExpiredCode() {
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.minusSeconds(1), claims()));

		assertThat(errorCodes(result)).containsExactly(TnfJwtValidator.EXPIRED);
	}

	@Test
	void treatsExpiryAsInclusive() {
		// Expiring "at" NOW means it is no longer valid at NOW, not for one more instant.
		assertThat(errorCodes(validator.validate(jwt(NOW, claims()))))
				.containsExactly(TnfJwtValidator.EXPIRED);
	}

	@Test
	void rejectsATokenWithNoExpiryAtAll() {
		// Otherwise a token with the exp claim stripped would never stop working.
		assertThat(errorCodes(validator.validate(jwt(null, claims()))))
				.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
	}

	@Test
	void rejectsATokenMissingAnyRequiredClaim() {
		for (String required : List.of("userId", "type", "roles", "collections")) {
			Map<String, Object> without = claims();
			without.remove(required);

			assertThat(errorCodes(validator.validate(jwt(NOW.plusSeconds(60), without))))
					.describedAs("a token with no %s must not authenticate", required)
					.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
		}
	}

	@Test
	void rejectsAnUnrecognisableUserType() {
		Map<String, Object> claims = claims();
		claims.put("type", "SUPERUSER");

		assertThat(errorCodes(validator.validate(jwt(NOW.plusSeconds(60), claims))))
				.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
	}

	@Test
	void acceptsAnIndividualWithNoInstitution() {
		// institutionId is genuinely optional: an individual subscriber belongs to none.
		Map<String, Object> claims = claims();
		claims.remove("institutionId");
		claims.put("type", "INDIVIDUAL");

		assertThat(validator.validate(jwt(NOW.plusSeconds(60), claims)).hasErrors()).isFalse();
	}

	@Test
	void rejectsAnIndividualThatClaimsAnInstitution() {
		// The escalation this closes: CurrentUser answers "do you belong to an institution?" from
		// institutionId alone, so an INDIVIDUAL carrying one passes requireSameInstitution for that
		// institution - a subscriber reading a tenant's resources. UserType says an individual never
		// carries one; this is where that stops being a comment.
		Map<String, Object> claims = claims();
		claims.put("type", "INDIVIDUAL");
		claims.put("institutionId", "inst_7f3");

		assertThat(errorCodes(validator.validate(jwt(NOW.plusSeconds(60), claims))))
				.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
	}

	@Test
	void rejectsAnInstitutionalUserWithNoInstitution() {
		// The other half of the same invariant: an institutional identity whose tenant we do not
		// know is not an identity we can make a tenant decision about.
		Map<String, Object> claims = claims();
		claims.remove("institutionId");

		assertThat(errorCodes(validator.validate(jwt(NOW.plusSeconds(60), claims))))
				.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
	}

	@Test
	void rejectsABlankInstitutionOnAnInstitutionalUser() {
		// Blank is not "absent": belongsToAnInstitution() treats it as no institution, so a token
		// carrying one would be an INSTITUTION user that fails every tenant check silently.
		Map<String, Object> claims = claims();
		claims.put("institutionId", "   ");

		assertThat(errorCodes(validator.validate(jwt(NOW.plusSeconds(60), claims))))
				.containsExactly(TnfJwtValidator.MISSING_CLAIMS);
	}

	private static List<String> errorCodes(OAuth2TokenValidatorResult result) {
		return result.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
	}

	private static Map<String, Object> claims() {
		Map<String, Object> claims = new HashMap<>();
		claims.put("userId", "usr_6712ab");
		claims.put("type", "INSTITUTION");
		claims.put("institutionId", "inst_7f3");
		claims.put("roles", List.of("MEMBER"));
		claims.put("collections", List.of("col_medicine"));
		return claims;
	}

	private static Jwt jwt(Instant expiresAt, Map<String, Object> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("signature-checked-elsewhere")
				.header("alg", "HS256")
				.claims(existing -> existing.putAll(claims));
		if (expiresAt != null) {
			builder.expiresAt(expiresAt);
		}
		return builder.build();
	}
}
