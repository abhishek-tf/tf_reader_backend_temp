package com.tf.reader.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;

/** Claims in, request identity out. Nothing else may influence the result. */
class CurrentUserJwtConverterTest {

	private final CurrentUserJwtConverter converter = new CurrentUserJwtConverter();

	@Test
	void mapsEveryClaimOntoTheCurrentUser() {
		CurrentUser user = principalOf(jwt(Map.of(
				"userId", "usr_6712ab",
				"type", "INSTITUTION",
				"institutionId", "inst_7f3",
				"roles", List.of("MEMBER"),
				"collections", List.of("col_medicine"))));

		assertThat(user.userId()).isEqualTo("usr_6712ab");
		assertThat(user.type()).isEqualTo(UserType.INSTITUTION);
		assertThat(user.institutionId()).isEqualTo("inst_7f3");
		assertThat(user.roles()).containsExactly("MEMBER");
		assertThat(user.collections()).containsExactly("col_medicine");
		assertThat(user.belongsToAnInstitution()).isTrue();
	}

	@Test
	void mapsRolesToPrefixedAuthorities() {
		// ROLE_ is not decoration: hasRole("ADMIN") looks for an authority named ROLE_ADMIN.
		AbstractAuthenticationToken authentication = converter.convert(jwt(Map.of(
				"userId", "usr_b920fe",
				"type", "INSTITUTION",
				"institutionId", "inst_7f3",
				"roles", List.of("MEMBER", "ADMIN"),
				"collections", List.of("col_medicine"))));

		assertThat(authentication.getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_MEMBER", "ROLE_ADMIN");
	}

	@Test
	void anIndividualHasNoInstitution() {
		CurrentUser user = principalOf(jwt(Map.of(
				"userId", "usr_9f01cd",
				"type", "INDIVIDUAL",
				"roles", List.of("SUBSCRIBER"),
				"collections", List.of("col_open"))));

		assertThat(user.institutionId()).isNull();
		assertThat(user.belongsToAnInstitution()).isFalse();
		assertThat(user.type()).isEqualTo(UserType.INDIVIDUAL);
	}

	@Test
	void theResultingAuthenticationIsAuthenticatedAndNamedByUserId() {
		AbstractAuthenticationToken authentication = converter.convert(memberJwt());

		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getName()).isEqualTo("usr_6712ab");
	}

	@Test
	void theTokenIsKeptAsCredentialsAndOutOfTheIdentity() {
		// CurrentUser answers "who is this", never "what would let me become them".
		AbstractAuthenticationToken authentication = converter.convert(memberJwt());

		assertThat(authentication.getCredentials()).isInstanceOf(Jwt.class);
		assertThat(CurrentUser.class.getRecordComponents())
				.extracting(RecordComponent::getName)
				.containsExactly("userId", "type", "institutionId", "roles", "collections");
	}

	@Test
	void readsNothingButTheClaims() {
		// Extra claims an attacker might smuggle in are ignored - the converter reads the five
		// it knows about and no others.
		CurrentUser user = principalOf(jwt(Map.of(
				"userId", "usr_6712ab",
				"type", "INSTITUTION",
				"institutionId", "inst_7f3",
				"roles", List.of("MEMBER"),
				"collections", List.of("col_medicine"),
				"admin", true,
				"scope", "everything")));

		assertThat(user.roles()).containsExactly("MEMBER");
		assertThat(user.userId()).isEqualTo("usr_6712ab");
	}

	private CurrentUser principalOf(Jwt jwt) {
		return (CurrentUser) converter.convert(jwt).getPrincipal();
	}

	private Jwt memberJwt() {
		return jwt(Map.of(
				"userId", "usr_6712ab",
				"type", "INSTITUTION",
				"institutionId", "inst_7f3",
				"roles", List.of("MEMBER"),
				"collections", List.of("col_medicine")));
	}

	/** A token the decoder would already have verified; the converter runs after that. */
	private Jwt jwt(Map<String, Object> claims) {
		return Jwt.withTokenValue("verified-elsewhere")
				.header("alg", "HS256")
				.claims(existing -> existing.putAll(claims))
				.issuedAt(Instant.parse("2026-08-13T14:42:00Z"))
				.expiresAt(Instant.parse("2026-08-13T15:42:00Z"))
				.build();
	}
}
