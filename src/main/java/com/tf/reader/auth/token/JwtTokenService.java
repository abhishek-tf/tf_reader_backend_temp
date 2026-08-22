package com.tf.reader.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.common.security.TokenAudience;
import com.tf.reader.common.security.TokenClaims;

/**
 * Issues the six-claim HS256 token the PRD specifies.
 *
 * <p><b>Exactly these claims, and no others:</b> {@code userId}, {@code type},
 * {@code institutionId}, {@code roles}, {@code collections}, {@code iat}, {@code exp}.
 *
 * <p>Notably absent, all deliberately:
 * <ul>
 * <li>no {@code kid} and no {@code aud} - there is one secret and one audience, us</li>
 * <li>no {@code sub}, {@code iss} or {@code jti} - the contract fixes the claim set, and three
 * other teams parse it. A claim nobody agreed to is a claim somebody will start depending on</li>
 * <li>no session id and no refresh token - a one-hour token and a fresh sign-in is the design</li>
 * </ul>
 *
 * <p><b>Why HS256 rather than RS256.</b> Anything that can verify an HS256 token can also mint
 * one, so there is no public key to hand to another team - which is exactly why nobody gets to
 * verify our tokens independently. wokay's document showing RS256 is their error to correct.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenService implements TokenService {

	private final JwtEncoder encoder;
	private final JwtProperties properties;
	private final Clock clock;

	public JwtTokenService(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		// Built from a bare JWK rather than through NimbusJwtEncoder.withSecretKey(), because
		// that builder derives a key id from the secret's SHA-256 thumbprint and stamps a kid
		// into every header. The PRD says no kid: with one secret it identifies nothing, it
		// publishes a fingerprint of the signing key, and it invites a rotation mechanism this
		// design does not have. A JWK with no key id produces no kid header.
		this.encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(properties.signingKey()));
	}

	@Override
	public IssuedToken issue(TnfUser user) {
		// Whole seconds because that is what the JWT numeric-date format holds; rounding here
		// rather than at the encoder keeps the value we report equal to the value in the token.
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(properties.ttl());

		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.subject(user.userId())
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
