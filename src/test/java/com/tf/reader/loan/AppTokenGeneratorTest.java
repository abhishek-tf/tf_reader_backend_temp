package com.tf.reader.loan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * NOT a real test. Run to get a Postman token for the app API (e.g. GET /api/v1/loans).
 *
 * <p>The app resource-server chain wants a {@code tf-app} access token, which is a different token
 * from the reader-auth one {@code TokenGeneratorTest} prints. This mints that one.
 *
 * <ol>
 *   <li>Make sure .env has {@code TF_JWT_SECRET=local-dev-only-tf-secret-for-dev-32chars}.</li>
 *   <li>Run: {@code ./mvnw test -Dtest=AppTokenGeneratorTest}</li>
 *   <li>Copy the token and paste it into Postman → Authorization → Bearer Token.</li>
 * </ol>
 */
class AppTokenGeneratorTest {

	/** Must match TF_JWT_SECRET in .env so the token verifies against the running app. */
	private static final String SECRET = "local-dev-only-tf-secret-for-dev-32chars";

	@Test
	void printPostmanAppToken() {
		SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("tf-reader")
				.subject("user_postman_01")
				.audience(List.of("tf-app"))
				.issuedAt(now)
				.expiresAt(now.plusSeconds(86400)) // 24 hours
				.claim("token_use", "access")
				.build();

		String token = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		System.out.println("\n\n======================================================");
		System.out.println("  POSTMAN APP TOKEN (tf-app) — valid 24h, local dev only");
		System.out.println("  sub=user_postman_01");
		System.out.println("======================================================");
		System.out.println(token);
		System.out.println("======================================================\n");
	}
}
