package com.tf.reader.auth.oidc.validation;

import com.tf.reader.auth.oidc.client.OidcTransaction;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Validates an ID token <b>against the sign-in it is supposed to belong to</b>.
 *
 * <p>Two stages, deliberately separate:
 *
 * <ol>
 * <li>{@link OidcIdTokenDecoder} answers "is this a genuine, current token from our provider,
 * for us?" - signature against the JWKS, issuer, audience, expiry. Those checks are the same for
 * every sign-in, so they are configuration, and they live in {@code auth.security} where the
 * architecture rules put token decoding.</li>
 * <li>This class answers "and is it <em>this</em> sign-in's token?" - the nonce, which is
 * different for every authorization request and can only be checked against the transaction
 * still held in {@link com.tf.reader.auth.oidc.client.OidcTransactionStore}.</li>
 * </ol>
 *
 * <p><b>Why the nonce matters even though the state already matched.</b> They bind different
 * things. State binds the <em>callback</em> to a sign-in this browser started; the nonce binds
 * the <em>token</em> to that same authorization request. Without it, a token obtained in some
 * other exchange - a different sign-in, a different application on the same tenant, a replay
 * captured earlier - could be presented into a flow whose state was legitimate, and everything
 * else about it would check out: real signature, right issuer, right audience, not expired. The
 * nonce is what makes that token belong to nobody but this request.
 */
@Component
public class OidcIdTokenValidator {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(OidcIdTokenValidator.class);

	private final OidcIdTokenDecoder decoder;

	public OidcIdTokenValidator(OidcIdTokenDecoder decoder) {
		this.decoder = decoder;
	}

	/**
	 * @param idToken     the raw ID token from the token endpoint
	 * @param transaction the sign-in it must belong to
	 * @return the verified token, whose claims are now safe to read
	 * @throws ApiException 401 if any check fails
	 */
	public Jwt validate(String idToken, OidcTransaction transaction) {
		if (!StringUtils.hasText(idToken)) {
			// A token response with no id_token is a provider configured without the openid scope.
			// It must refuse rather than sign somebody in on an access token, which is an
			// authorization grant and not an assertion about who anybody is.
			throw new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
					"The identity provider returned no ID token.");
		}

		Jwt verified = this.decoder.verify(idToken);
		requireMatchingNonce(verified, transaction);

		log.debug("OIDC ID token accepted for transaction {}", transaction.id());
		return verified;
	}

	private static void requireMatchingNonce(Jwt idToken, OidcTransaction transaction) {
		String presented = idToken.getClaimAsString("nonce");

		// Absent is a failure, not a pass. "If the value is present, compare it" is the shape of
		// this check that does nothing: a token minted without a nonce would sail through it.
		if (!StringUtils.hasText(presented) || !constantTimeEquals(presented, transaction.nonce())) {
			log.warn("OIDC nonce mismatch for transaction {} - the token was not minted for this "
					+ "authorization request", transaction.id());
			throw new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
					"The identity provider's token could not be validated.");
		}
	}

	/**
	 * Compared without an early exit.
	 *
	 * <p>Timing-safe comparison of a nonce is close to superstition - it is single use, it lives
	 * ten minutes, and an attacker gets one guess per sign-in they can start. It costs one method
	 * call, and the habit is worth more than the reasoning: the next value someone compares this
	 * way may be one where it matters.
	 */
	private static boolean constantTimeEquals(String presented, String expected) {
		return MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}
}
