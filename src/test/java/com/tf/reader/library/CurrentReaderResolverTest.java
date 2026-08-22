package com.tf.reader.library;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.security.CurrentUserAuthenticationToken;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.library.support.CurrentReaderResolver;
import com.tf.reader.library.support.ReaderIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentReaderResolverTest {

	private final CurrentReaderResolver resolver = new CurrentReaderResolver();

	@Test
	@DisplayName("identity is taken from the principal the auth module already built")
	void mapsTheRequestPrincipal() {
		ReaderIdentity reader = resolver.require(authenticated(
				new CurrentUser("user_9c2", UserType.INSTITUTION, "inst_7f3",
						List.of("MEMBER"), List.of("col_1"))));

		assertThat(reader.userId()).isEqualTo("user_9c2");
		assertThat(reader.institutionId()).isEqualTo("inst_7f3");
		assertThat(reader.belongsToAnInstitution()).isTrue();
	}

	@Test
	@DisplayName("an individual subscriber belongs to no institution and is not defaulted into one")
	void institutionIsOptional() {
		ReaderIdentity reader = resolver.require(authenticated(
				new CurrentUser("user_solo", UserType.INDIVIDUAL, null,
						List.of("MEMBER"), List.of())));

		assertThat(reader.institutionId()).isNull();
		assertThat(reader.belongsToAnInstitution()).isFalse();
	}

	@Test
	@DisplayName("no authentication is 401, not an empty library")
	void deniesWithoutAuthentication() {
		assertThatThrownBy(() -> resolver.require(null))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
						.isEqualTo(ErrorCode.UNAUTHENTICATED));
	}

	@Test
	@DisplayName("an authentication that is not a verified reader identity is refused")
	void deniesAnythingThatIsNotACurrentUser() {
		// Anything authenticated by some other means — a leftover session, a test double — is
		// refused rather than mined for a userId. The principal here is a bare String.
		TestingAuthenticationToken notAReader = new TestingAuthenticationToken(
				"user_9c2", "password", List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

		assertThatThrownBy(() -> resolver.require(notAReader)).isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("an unauthenticated token is refused even when it carries a reader")
	void deniesAnUnauthenticatedToken() {
		// A well formed identity object is not the same thing as a verified signature.
		TestingAuthenticationToken unverified = new TestingAuthenticationToken(
				new CurrentUser("user_9c2", UserType.INSTITUTION, "inst_7f3",
						List.of("MEMBER"), List.of()),
				null);
		assertThat(unverified.isAuthenticated()).isFalse();

		assertThatThrownBy(() -> resolver.require(unverified)).isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("a reader with a blank userId is refused, not carried into a query")
	void deniesABlankUserId() {
		assertThatThrownBy(() -> resolver.require(authenticated(
				new CurrentUser("   ", UserType.INSTITUTION, "inst_7f3",
						List.of("MEMBER"), List.of()))))
				.isInstanceOf(ApiException.class);
	}

	/** What the API filter chain leaves in the security context for a verified bearer token. */
	private static CurrentUserAuthenticationToken authenticated(CurrentUser reader) {
		return new CurrentUserAuthenticationToken(reader, null,
				List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
	}

}
