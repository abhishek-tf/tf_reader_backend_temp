package com.tf.reader.auth.oidc.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.repository.MockUserRepository;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * B2C claims to {@link TnfUser}, and the claims that are deliberately ignored.
 *
 * <p>The mirror of {@code SamlUserMapperTest}. No B2C tenant, no network and no Spring context:
 * the mapper's whole job is arithmetic on a claim set, and the claim set is the one thing a test
 * can construct exactly.
 */
class OidcUserMapperTest {

	private final OidcUserMapper mapper =
			new OidcUserMapper(new MockUserRepository(), OidcProperties.forIssuer(ISSUER));

	private static final String ISSUER = "https://tnf.b2clogin.com/00000000-0000-0000-0000-000000000000/v2.0/";

	// ───────────────────────────── the happy path ─────────────────────────────

	@Test
	void aB2cEmailsArrayResolvesToTheSeededMembership() {
		// "emails" as a JSON array is what an Azure AD B2C user flow actually emits, and it is the
		// reason the mapper reads a claim that may be a list rather than calling getClaimAsString.
		TnfUser user = mapper.map(idToken(Map.of(
				"emails", List.of("john.doe@example.com"),
				"oid", "b2c-object-id")), "inst_imperial");

		assertThat(user.userId()).isEqualTo("usr_6712ab");
		assertThat(user.institutionId()).isEqualTo("inst_imperial");
		assertThat(user.type()).isEqualTo(UserType.INSTITUTION);
		assertThat(user.roles()).containsExactly("MEMBER");
		assertThat(user.collections()).containsExactly("col_medicine");
	}

	@Test
	void aPlainEmailClaimWorksToo() {
		// Entra External ID and the Microsoft identity platform emit a string, not an array.
		assertThat(mapper.map(idToken(Map.of("email", "john.doe@example.com")), "inst_dsu").userId())
				.isEqualTo("usr_8c14de");
	}

	@Test
	void theClaimsAreTriedInTheConfiguredOrder() {
		// emails, then email, then preferred_username, then upn. A token carrying several must
		// resolve by the most preferred one, or the mapping depends on map iteration order.
		TnfUser user = mapper.map(idToken(Map.of(
				"emails", List.of("jane.roe@example.com"),
				"email", "john.doe@example.com",
				"preferred_username", "someone.else@example.com")), "inst_imperial");

		assertThat(user.userId()).isEqualTo("usr_b920fe");
	}

	@Test
	void aLaterClaimIsUsedWhenTheEarlierOnesAreAbsentOrBlank() {
		// A B2C user flow that has not been given the "emails" application claim emits it empty
		// rather than omitting it, so "present but useless" has to fall through like "absent".
		assertThat(mapper.map(idToken(Map.of(
				"emails", List.of(),
				"email", "   ",
				"preferred_username", "john.doe@example.com")), "inst_xyz").userId())
				.isEqualTo("usr_3f81ab");
	}

	@Test
	void theClaimNamesAreConfigurable() {
		// A tenant emitting its email somewhere else is a configuration change, not a code change.
		OidcUserMapper custom = new OidcUserMapper(new MockUserRepository(),
				OidcProperties.withClaims(
						new OidcProperties.Claims(List.of("mail"), List.of("uid"))));

		assertThat(custom.map(idToken(Map.of("mail", "john.doe@example.com")), "inst_imperial")
				.userId()).isEqualTo("usr_6712ab");
		assertThat(custom.resolveSubject(idToken(Map.of("uid", "u-1", "oid", "ignored"))))
				.isEqualTo("u-1");
	}

	@Test
	void theEmailIsFoldedByTheRepositoryNotByTheClaim() {
		// B2C is free to vary the case of an address it round-trips. The repository lower-cases;
		// this pins that a mixed-case claim still finds the seeded user.
		assertThat(mapper.map(idToken(Map.of("email", "John.Doe@Example.COM")), "inst_imperial")
				.userId()).isEqualTo("usr_6712ab");
	}

	// ───────────── the claims that must NOT influence authorization ─────────────

	@Test
	void aRolesClaimCannotGrantAnApplicationRole() {
		// The headline security property of this class. Anybody able to edit a B2C user flow's
		// output claims - or to add an app role in the tenant - would otherwise be an ADMIN here.
		TnfUser user = mapper.map(idToken(Map.of(
				"email", "john.doe@example.com",
				"roles", List.of("ADMIN"),
				"role", "ADMIN",
				"groups", List.of("ADMIN"),
				"permissions", List.of("*"),
				"extension_roles", "ADMIN")), "inst_imperial");

		assertThat(user.roles()).containsExactly("MEMBER");
	}

	@Test
	void aCollectionsClaimCannotGrantEntitlements() {
		TnfUser user = mapper.map(idToken(Map.of(
				"email", "john.doe@example.com",
				"collections", List.of("col_everything"))), "inst_imperial");

		assertThat(user.collections()).containsExactly("col_medicine");
	}

	@Test
	void anInstitutionClaimCannotChooseTheTenant() {
		// The institution comes from the sign-in transaction. A claim asking for another one is
		// ignored, and the user resolved is the one at the institution we were passed.
		TnfUser user = mapper.map(idToken(Map.of(
				"email", "john.doe@example.com",
				"institutionId", "inst_imperial",
				"extension_institution", "inst_imperial")), "inst_dsu");

		assertThat(user.institutionId()).isEqualTo("inst_dsu");
		assertThat(user.userId()).isEqualTo("usr_8c14de");
	}

	@Test
	void aTypeClaimCannotTurnAnInstitutionalMemberIntoAnIndividual() {
		assertThat(mapper.map(idToken(Map.of(
				"email", "john.doe@example.com",
				"type", "INDIVIDUAL")), "inst_imperial").type())
				.isEqualTo(UserType.INSTITUTION);
	}

	@Test
	void oneIdentityIsADifferentUserAtEachInstitution() {
		// Same claim set, three transactions, three users - the same property the SAML mapper has,
		// and the reason both mappers end at the same (email, institutionId) lookup.
		Map<String, Object> claims = Map.of("email", "john.doe@example.com");

		assertThat(mapper.map(idToken(claims), "inst_imperial").userId()).isEqualTo("usr_6712ab");
		assertThat(mapper.map(idToken(claims), "inst_dsu").userId()).isEqualTo("usr_8c14de");
		assertThat(mapper.map(idToken(claims), "inst_xyz").userId()).isEqualTo("usr_3f81ab");
	}

	// ───────────────────────────── refusals ─────────────────────────────

	@Test
	void anIdentityWithNoMembershipIsRefusedAsUnprovisioned() {
		// Authenticated by B2C is not provisioned by us, and the two refusals are different: 403
		// here, not 401, because the token was fine and the person is simply not a member.
		assertThatThrownBy(() -> mapper.map(idToken(Map.of("email", "stranger@example.com")),
				"inst_imperial"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void aMembershipAtAnotherInstitutionIsStillNoMembershipHere() {
		// jane.roe is seeded at Imperial only.
		assertThatThrownBy(() -> mapper.map(idToken(Map.of("email", "jane.roe@example.com")),
				"inst_dsu"))
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void aTokenWithNoEmailClaimAtAllIsRefused() {
		// Refused rather than defaulted to the subject: a lookup by something that is not an email
		// address would either miss every time or, worse, one day hit.
		assertThatThrownBy(() -> mapper.map(idToken(Map.of("oid", "b2c-object-id", "name", "John")),
				"inst_imperial"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.OIDC_AUTHENTICATION_FAILED);
	}

	@Test
	void anEmailClaimOfTheWrongTypeIsSkippedRatherThanCoerced() {
		// String.valueOf on a map or a number would produce a plausible-looking lookup key out of
		// nothing. Neither of these is a string, so neither is an email, so this refuses.
		assertThatThrownBy(() -> mapper.map(
				idToken(Map.of("emails", Map.of("value", "john.doe@example.com"), "email", 42)),
				"inst_imperial"))
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.OIDC_AUTHENTICATION_FAILED);
	}

	@Test
	void anUnknownInstitutionCannotProduceAUser() {
		assertThatThrownBy(() -> mapper.map(idToken(Map.of("email", "john.doe@example.com")),
				"inst_nowhere"))
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	// ───────────────────────────── the subject ─────────────────────────────

	@Test
	void theSubjectPrefersOidOverSub() {
		// B2C's "sub" is pairwise - a different value per application - while "oid" is the
		// directory object id and is the same across every app in the tenant.
		assertThat(mapper.resolveSubject(idToken(Map.of("sub", "pairwise", "oid", "object-id"))))
				.isEqualTo("object-id");
		assertThat(mapper.resolveSubject(idToken(Map.of("sub", "pairwise"))))
				.isEqualTo("pairwise");
	}

	@Test
	void aTokenWithNoSubjectClaimStillSignsIn() {
		// The subject is evidence for the audit trail, not identity. Its absence is not a refusal.
		assertThat(mapper.resolveSubject(idToken(Map.of("email", "john.doe@example.com")))).isNull();
		assertThat(mapper.map(idToken(Map.of("email", "john.doe@example.com")), "inst_imperial"))
				.isNotNull();
	}

	/**
	 * An ID token with the given claims. Never signed and never verified here - this class tests
	 * what the mapper does with a token Spring Security has <em>already</em> validated, and the
	 * validation itself is {@code OidcIdTokenValidationTest}'s subject.
	 */
	private static Jwt idToken(Map<String, Object> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("id-token-value")
				.header("alg", "RS256")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300));
		claims.forEach(builder::claim);
		return builder.build();
	}
}
