package com.tf.reader.auth.oidc.mock.security;

import com.tf.reader.auth.oidc.mock.config.MockOidcComponent;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;


import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The mock provider's signing key.
 *
 * <p><b>Generated in memory at startup, and never written anywhere.</b> That is the whole
 * answer to "do not commit the private key": there is no file to commit, no path to leak and no
 * key material in the repository at all. It also means the key is different on every run, which
 * is realistic - a relying party must fetch the JWKS rather than pin a key it remembers - and it
 * makes it impossible for a token minted by yesterday's process to be accepted by today's.
 *
 * <p>RSA 2048 and RS256: what B2C uses, so the client's decoder is configured for the same
 * algorithm it will meet in production rather than for something only the mock can produce.
 *
 * <p><b>The {@code kid} matters.</b> A JWKS may hold several keys - during a rotation it always
 * does - and the relying party picks one by the {@code kid} in the token header. Minting tokens
 * with no {@code kid}, or publishing a JWKS without one, would work here (there is only ever one
 * key) and then fail against a real provider, which is the worst kind of mock: one that lets a
 * bug through until production.
 */
@MockOidcComponent
public class MockOidcKeyService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MockOidcKeyService.class);

	private static final int KEY_SIZE = 2048;

	private final RSAKey key;

	public MockOidcKeyService() {
		this.key = generate();
		// The key id is safe to log and is the one thing worth seeing when a signature is refused:
		// it tells you whether the token and the JWKS are talking about the same key. The private
		// key is of course never logged, and there is no accessor that would return it as a string.
		log.info("Mock OIDC provider generated a fresh RS256 signing key: kid={}", key.getKeyID());
	}

	/**
	 * The public half, in JWKS form, exactly as the {@code /oauth2/jwks} endpoint serves it.
	 *
	 * <p>{@code toPublicJWK()} is not decoration: {@code RSAKey} holds both halves, and
	 * serialising the whole thing would publish the private key at a public endpoint. The
	 * conversion is what makes the JWKS a public document.
	 */
	public Map<String, Object> jwkSet() {
		return new JWKSet(key.toPublicJWK()).toJSONObject();
	}

	/**
	 * Signs a claims set as this provider.
	 *
	 * <p><b>The private key never leaves this class.</b> Splitting the mock into packages made the
	 * old package-private {@code privateKey()} accessor unreachable from the token service, and the
	 * obvious fix - making it public - would have published a getter for a signing key. Moving the
	 * signing in here instead is strictly better: there is now no way for any caller, test included,
	 * to obtain the key at all.
	 *
	 * <p>The {@code kid} goes in the header, because a JWKS may hold several keys - during a
	 * rotation it always does - and the relying party picks one by it. A mock that omitted it would
	 * work here, where there is only ever one key, and fail against a real provider.
	 */
	public String sign(JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
			jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
			return jwt.serialize();
		}
		catch (JOSEException failure) {
			throw new IllegalStateException("the mock provider could not sign a token", failure);
		}
	}

	private static RSAKey generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(KEY_SIZE);
			KeyPair pair = generator.generateKeyPair();

			return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
					.privateKey((RSAPrivateKey) pair.getPrivate())
					.keyID(UUID.randomUUID().toString())
					.build();
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("RSA is required of every JVM", impossible);
		}
	}
}
