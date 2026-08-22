package com.tf.reader.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing configuration, bound from {@code tnf.auth.jwt.*}.
 *
 * <p><b>The application refuses to start without a secret.</b> That is a requirement, not a
 * nicety: a fallback default would mean every deployment that forgot to set one shared a
 * publicly known signing key, and anything that can verify an HS256 token can also mint one.
 * The failure has to be loud and at startup, because a token signed with a default key is
 * indistinguishable from a real one afterwards.
 *
 * <p>The secret is never written down here. It comes from the environment.
 *
 * @param secret the HS256 signing secret; at least 32 bytes, since HS256 is a 256-bit MAC
 * @param ttl    how long an issued token lives. One hour, per the PRD - there is no refresh
 *               token, so the app simply signs in again
 */
@ConfigurationProperties(prefix = "tnf.auth.jwt")
public record JwtProperties(String issuer, String secret, Duration ttl) {

	/** The issuer claim put into every token. Validated on inbound tokens to reject foreign ones. */
	private static final String DEFAULT_ISSUER = "tf-reader";

	/** HS256 is a 256-bit MAC; a shorter key would be rejected by the signer anyway. */
	static final int MINIMUM_SECRET_BYTES = 32;

	private static final Duration DEFAULT_TTL = Duration.ofHours(1);

	public JwtProperties {
		issuer = (issuer == null || issuer.isBlank()) ? DEFAULT_ISSUER : issuer;
		// An unresolved ${...} placeholder arrives as its own literal text rather than as null,
		// so without this the failure reports "secret too short" and sends whoever is deploying
		// off to lengthen a secret that was never set.
		if (secret == null || secret.isBlank() || secret.startsWith("${")) {
			throw new IllegalStateException(
					"tnf.auth.jwt.secret is not set. Provide it through the TNF_JWT_SECRET "
							+ "environment variable; there is deliberately no default.");
		}
		if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
			throw new IllegalStateException("tnf.auth.jwt.secret must be at least "
					+ MINIMUM_SECRET_BYTES + " bytes for HS256.");
		}
		ttl = (ttl != null) ? ttl : DEFAULT_TTL;
		if (ttl.isZero() || ttl.isNegative()) {
			throw new IllegalStateException("tnf.auth.jwt.ttl must be positive.");
		}
	}

	public SecretKey signingKey() {
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	/**
	 * Redacts the secret.
	 *
	 * <p>A record's generated {@code toString()} prints every component, so the default would put
	 * the HS256 signing key in clear into any log line, binding error or exception that happened
	 * to carry this object. Anything able to <em>verify</em> one of our tokens can also
	 * <em>mint</em> one, so leaking the key is not a partial disclosure - it is every account.
	 *
	 * <p>Length is safe to report and is the one thing worth knowing when a deployment is
	 * misconfigured.
	 */
	@Override
	public String toString() {
		return "JwtProperties[issuer=" + issuer + ", secret=<redacted, " + secret.length() + " chars>, ttl=" + ttl + "]";
	}
}
