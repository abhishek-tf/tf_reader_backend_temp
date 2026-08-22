package com.tf.reader.auth.security;

import java.time.Clock;

import javax.crypto.SecretKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The verifying half of the token design, for this module's own {@code /api/v1/auth/**} chain.
 *
 * <p>Takes the <b>same signing key</b> {@code common.security.JwtConfig} injects into the
 * encoder — the {@code jwtSigningKey} bean it exposes — rather than deriving one of its own from
 * a separate {@code JwtProperties}, so the secret cannot drift apart: there is one secret in the
 * application and one place it is built. A second signing mechanism, or a decoder given its own
 * copy of the key, is how a service ends up unable to verify tokens it minted itself. (This
 * decoder previously depended on {@code auth.token.JwtProperties}, which stopped being a
 * registered bean once the app migrated to the shared {@code common.security.JwtProperties} —
 * every context using this bean failed to start until this was repointed.)
 */
@Configuration
public class JwtDecoderConfig {

	@Bean
	public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// Replaces Spring's default validator chain outright. The default checks expiry with
		// the system clock and reports it through a message string; ours checks expiry and the
		// claims we actually require, with codes the entry point can branch on.
		decoder.setJwtValidator(new TnfJwtValidator(clock));
		return decoder;
	}
}
