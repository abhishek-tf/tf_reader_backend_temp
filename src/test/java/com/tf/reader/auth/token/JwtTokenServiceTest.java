package com.tf.reader.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jwt.SignedJWT;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;

/**
 * The token is what three other teams parse, so these tests are about the exact claim set as
 * much as about correctness.
 */
class JwtTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-13T14:42:00Z");
	private static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_imperial", List.of("MEMBER"), List.of("col_medicine"));

	private final TokenService tokenService =
			JwtTokenService.forTest(SECRET, Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC));

	private final JwtDecoder decoder = decoderAtTheTestsClock();

	/**
	 * A decoder that judges expiry by the same clock the tokens here are minted with.
	 *
	 * <p>{@code NimbusJwtDecoder} installs Spring's default validator chain, and that chain reads
	 * the <b>system</b> clock - so a token minted at a fixed instant is only decodable for an hour
	 * of real time after it, and this test would start failing on the hour with "Jwt expired". The
	 * issuer is fixed-clock, so the verifier has to be as well.
	 */
	private JwtDecoder decoderAtTheTestsClock() {
		byte[] keyBytes = SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var signingKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256");
		NimbusJwtDecoder built = NimbusJwtDecoder.withSecretKey(signingKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		built.setJwtValidator(new com.tf.reader.auth.security.TnfJwtValidator(
				Clock.fixed(NOW, ZoneOffset.UTC)));
		return built;
	}

	@Test
	void issuesAVerifiableTokenForAnAuthenticatedUser() {
		IssuedToken issued = tokenService.issue(MEMBER);

		assertThat(issued.token()).isNotBlank();
		// Decoding verifies the signature: if it were signed with anything else, this throws.
		assertThat(decoder.decode(issued.token())).isNotNull();
	}

	@Test
	void carriesTheUserIdentity() {
		Jwt jwt = decode(MEMBER);

		assertThat(jwt.getClaimAsString("userId")).isEqualTo("usr_6712ab");
		assertThat(jwt.getClaimAsString("type")).isEqualTo("INSTITUTION");
		assertThat(jwt.getClaimAsString("institutionId")).isEqualTo("inst_imperial");
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MEMBER");
		assertThat(jwt.getClaimAsStringList("collections")).containsExactly("col_medicine");
	}

	@Test
	void carriesIssuedAtAndExpiry() {
		IssuedToken issued = tokenService.issue(MEMBER);
		Jwt jwt = decoder.decode(issued.token());

		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
		// What we report to the caller must equal what is inside the token, or the app counts
		// down to a different moment than the one the server will refuse at.
		assertThat(issued.issuedAt()).isEqualTo(jwt.getIssuedAt());
		assertThat(issued.expiresAt()).isEqualTo(jwt.getExpiresAt());
	}

	@Test
	void expiresAfterExactlyTheConfiguredLifetime() {
		TokenService tenMinutes = JwtTokenService.forTest(SECRET, Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(tenMinutes.issue(MEMBER).expiresAt()).isEqualTo(NOW.plusSeconds(600));
	}

	@Test
	void isSignedWithHs256() throws ParseException {
		SignedJWT parsed = SignedJWT.parse(tokenService.issue(MEMBER).token());

		assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
	}

	@Test
	void carriesExactlyTheClaimsTheContractFixes() {
		// iss, aud, and token_use are required by the app decoder; the identity claims are
		// the business data. No kid, no sub, no jti, no session id.
		assertThat(decode(MEMBER).getClaims())
				.containsOnlyKeys("iss", "aud", "token_use", "userId", "type", "institutionId",
						"roles", "collections", "iat", "exp");
	}

	@Test
	void omitsInstitutionIdForAnIndividualSubscriber() {
		// An individual belongs to no institution; the claim is absent, not null.
		TnfUser individual = new TnfUser("usr_9f01cd", UserType.INDIVIDUAL, null,
				List.of("SUBSCRIBER"), List.of("col_open"));

		Jwt jwt = decode(individual);

		assertThat(jwt.getClaims()).doesNotContainKey("institutionId");
		assertThat(jwt.getClaimAsString("type")).isEqualTo("INDIVIDUAL");
	}

	@Test
	void headerCarriesNoKeyIdentifier() {
		// There is one secret, so a kid identifies nothing and only invites a key-rotation
		// mechanism the design does not have.
		assertThat(decode(MEMBER).getHeaders()).doesNotContainKey("kid");
	}

	@Test
	void everyClaimComesFromTheUserObjectAndNowhereElse() {
		// Two users differing only in their fields produce tokens differing only in those
		// claims: nothing is read from ambient state, a request, or a thread local.
		TnfUser other = new TnfUser("usr_8c14de", UserType.INSTITUTION, "inst_dsu",
				List.of("MEMBER", "ADMIN"), List.of("col_engineering"));

		Jwt jwt = decode(other);

		assertThat(jwt.getClaimAsString("userId")).isEqualTo("usr_8c14de");
		assertThat(jwt.getClaimAsString("institutionId")).isEqualTo("inst_dsu");
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MEMBER", "ADMIN");
	}

	@Test
	void acceptsNothingButAnAlreadyAuthenticatedUser() {
		// The security property of the whole class: there is no way to ask for a token by
		// supplying an email, a userId or a role list. If an overload ever appears that takes a
		// String, a request body can reach it.
		Method[] issueMethods = java.util.Arrays.stream(TokenService.class.getMethods())
				.filter(method -> method.getName().equals("issue"))
				.toArray(Method[]::new);

		assertThat(issueMethods).hasSize(1);
		assertThat(issueMethods[0].getParameterTypes()).containsExactly(TnfUser.class);
	}

	@Test
	void containsNoSamlLogicAtAll() {
		// TokenService must not authenticate, look users up, or resolve institutions. If it
		// collaborates with a SAML type or a repository, one of those has crept in.
		List<Class<?>> collaborators = java.util.stream.Stream
				.concat(java.util.Arrays.stream(JwtTokenService.class.getDeclaredFields())
						.map(java.lang.reflect.Field::getType),
						java.util.Arrays.stream(JwtTokenService.class.getDeclaredConstructors())
								.map(Constructor::getParameterTypes).flatMap(java.util.Arrays::stream))
				.toList();

		assertThat(collaborators).allSatisfy(type -> assertThat(type.getName())
				.doesNotContain("saml")
				.doesNotContain("Saml")
				.doesNotContain("Repository"));
	}

	private Jwt decode(TnfUser user) {
		return decoder.decode(tokenService.issue(user).token());
	}
}
