package com.tf.reader.auth.security;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.common.security.TokenAudience;

/**
 * The verifying half of the token design.
 *
 * <p>Takes the <b>same {@link JwtProperties} bean</b> the encoder does, so the secret and the
 * algorithm cannot drift apart: there is one secret in the application and one place it is
 * configured. A second signing mechanism, or a decoder given its own copy of the key, is how a
 * service ends up unable to verify tokens it minted itself.
 *
 * <p><b>Issuer and audience are enforced here.</b> {@link TnfJwtValidator} now receives the
 * expected issuer from {@link JwtProperties} and the fixed app audience {@link TokenAudience#APP},
 * so a token signed with the same key but carrying the wrong issuer or a different audience
 * (such as an admin token) is refused during decoding, before any controller runs.
 */
@Configuration
public class JwtDecoderConfig {

	@Bean
	public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.signingKey())
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// Pass the issuer string from config and the fixed app audience into the validator.
		// TnfJwtValidator now checks: expiry, iss, aud, token_use, and all identity claims.
		decoder.setJwtValidator(new TnfJwtValidator(properties.issuer(), TokenAudience.APP, clock));
		return decoder;
	}
}
