package com.tf.reader.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.common.security.JwtProperties;
import com.tf.reader.common.security.TokenAudience;
import com.tf.reader.common.security.TokenClaims;

/**
 * Issues the HS256 token the PRD specifies, with the claims required by the app decoder.
 *
 * <p><b>Claims emitted:</b> {@code iss}, {@code aud}, {@code token_use}, {@code userId},
 * {@code type}, {@code institutionId} (when present), {@code roles}, {@code collections},
 * {@code iat}, {@code exp}.
 *
 * <p>{@code iss} and {@code aud} are validated by {@code JwtConfig.appAccessTokenDecoder},
 * which guards all of {@code /api/v1/**} outside the auth chain. Tokens that omit them are
 * rejected before any controller runs.
 *
 * <p>Uses the shared {@link JwtEncoder} bean from {@code JwtConfig} so the signing key is
 * identical to the one the app decoder verifies against. Separate key beans would mean tokens
 * minted here fail signature verification on every other endpoint.
 *
 * <p><b>Why HS256 rather than RS256.</b> Anything that can verify an HS256 token can also mint
 * one, so there is no public key to hand to another team - which is exactly why nobody gets to
 * verify our tokens independently.
 */
@Service
public class JwtTokenService implements TokenService {

	private final JwtEncoder encoder;
	private final JwtProperties jwtProperties;
	private final Duration ttl;
	private final Clock clock;

	/**
	 * Production constructor — injected by Spring. Uses the shared encoder and common JwtProperties.
	 *
	 * <p>Explicitly {@code @Autowired} because this class also declares a private constructor for
	 * {@link #forTest}; with two constructors and neither annotated, Spring's autowiring falls back
	 * to a no-arg constructor that does not exist, and every context using this bean fails to start.
	 */
	@Autowired
	public JwtTokenService(JwtEncoder encoder, JwtProperties jwtProperties, Clock clock) {
		this.encoder = encoder;
		this.jwtProperties = jwtProperties;
		this.ttl = jwtProperties.accessTokenTtl();
		this.clock = clock;
	}

	/**
	 * Test-only constructor: builds its own encoder from a raw secret so unit tests don't
	 * need a full Spring context. Uses {@code "tf-reader"} as the issuer (matching the
	 * default in {@code JwtProperties}) and the supplied TTL.
	 */
	public static JwtTokenService forTest(String secret, Duration ttl, Clock clock) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		var signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
		JwtEncoder enc = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
		// Fake JwtProperties by building a real decoder; we only need issuer + ttl
		return new JwtTokenService(enc, null, ttl, clock, "tf-reader");
	}

	/** Internal full constructor used by {@link #forTest}. */
	private JwtTokenService(JwtEncoder encoder, JwtProperties jwtProperties, Duration ttl,
			Clock clock, String issuerOverride) {
		this.encoder = encoder;
		this.jwtProperties = jwtProperties;
		this.ttl = ttl;
		this.clock = clock;
		this.issuerOverride = issuerOverride;
	}

	/** Non-null only in test instances built via {@link #forTest}. */
	private String issuerOverride;

	private String resolvedIssuer() {
		return issuerOverride != null ? issuerOverride
				: (jwtProperties != null ? jwtProperties.issuer() : "tf-reader");
	}

	private Duration resolvedTtl() {
		return ttl;
	}

	@Override
	public IssuedToken issue(TnfUser user) {
		// Whole seconds because that is what the JWT numeric-date format holds; rounding here
		// rather than at the encoder keeps the value we report equal to the value in the token.
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(resolvedTtl());

		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(resolvedIssuer())
				.audience(List.of(TokenAudience.APP))
				.claim(TokenClaims.TOKEN_USE, TokenClaims.USE_ACCESS)
				.claim("userId", user.userId())
				.claim("type", user.type().name())
				.claim("roles", user.roles())
				.claim("collections", user.collections())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt);

		// An individual subscriber belongs to no institution, and the claim is absent rather
		// than null - a null would have every consumer writing a null check for a case that
		// simply means "not an institutional member".
		if (StringUtils.hasText(user.institutionId())) {
			claims.claim("institutionId", user.institutionId());
		}

		String token = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();

		return new IssuedToken(token, issuedAt, expiresAt);
	}
}
