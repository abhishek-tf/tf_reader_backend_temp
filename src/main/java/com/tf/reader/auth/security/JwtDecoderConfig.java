package com.tf.reader.auth.security;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.tf.reader.auth.token.JwtProperties;

/**
 * The verifying half of the token design.
 *
 * <p>Takes the <b>same {@link JwtProperties} bean</b> the encoder does, so the secret and the
 * algorithm cannot drift apart: there is one secret in the application and one place it is
 * configured. A second signing mechanism, or a decoder given its own copy of the key, is how a
 * service ends up unable to verify tokens it minted itself.
 */
@Configuration
public class JwtDecoderConfig {

	@Bean
	public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.signingKey())
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// Replaces Spring's default validator chain outright. The default checks expiry with
		// the system clock and reports it through a message string; ours checks expiry and the
		// claims we actually require, with codes the entry point can branch on.
		decoder.setJwtValidator(new TnfJwtValidator(clock));
		return decoder;
	}
}
