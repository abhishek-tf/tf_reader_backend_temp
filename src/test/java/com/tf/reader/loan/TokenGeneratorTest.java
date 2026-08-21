package com.tf.reader.loan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * NOT a real test. Run to get a Postman token for local testing.
 *
 * 1. Make sure the .env file has TNF_JWT_SECRET=local-dev-only-not-a-real-secret-32ch
 * 2. Run: ./mvnw test -Dtest=TokenGeneratorTest
 * 3. Copy the token printed in the console output
 * 4. In Postman → Authorization tab → Bearer Token → paste it
 */
class TokenGeneratorTest {

	private static final String SECRET = "local-dev-only-not-a-real-secret-32ch";

	@Test
	void printPostmanToken() {
		SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.claim("userId", "user_postman_01")
				.claim("type", "INSTITUTION")
				.claim("institutionId", "inst_tandf_01")
				.claim("roles", List.of("MEMBER"))
				.claim("collections", List.of("col_1"))
				.issuedAt(now)
				.expiresAt(now.plusSeconds(86400)) // 24 hours
				.build();

		String token = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		System.out.println("\n\n======================================================");
		System.out.println("  POSTMAN TOKEN — valid 24h, local dev only");
		System.out.println("======================================================");
		System.out.println(token);
		System.out.println("======================================================\n");
	}
}
