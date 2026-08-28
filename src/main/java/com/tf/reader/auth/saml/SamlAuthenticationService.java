package com.tf.reader.auth.saml;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.stereotype.Service;

import com.tf.reader.auth.model.Institution;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.token.IssuedToken;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Joins the two halves of a completed SAML sign-in: the identity the IdP asserted, and the
 * institution our own backend chose before the redirect.
 *
 * <p>Spring Security has already answered "was this external identity authenticated?". This
 * class answers "which sign-in was it, and which of our users is it?" - and nothing else. It
 * knows nothing about HTTP, which is what keeps it unit-testable without a servlet.
 *
 * <p><b>Where this stops.</b> It returns a {@link TnfUser} and mints nothing. TokenService, the
 * JWT and the session are the next stage of work, and this is the seam they will attach to.
 */
@Service
public class SamlAuthenticationService {

	private final AuthTransactionStore transactions;
	private final InstitutionLookup institutions;
	private final SamlUserMapper userMapper;
	private final TokenService tokenService;
	private final Clock clock;

	public SamlAuthenticationService(AuthTransactionStore transactions,
			InstitutionLookup institutions, SamlUserMapper userMapper,
			TokenService tokenService, Clock clock) {
		this.transactions = transactions;
		this.institutions = institutions;
		this.userMapper = userMapper;
		this.tokenService = tokenService;
		this.clock = clock;
	}

	/**
	 * Completes a sign-in that Spring Security has already validated.
	 *
	 * @param authentication the validated SAML authentication
	 * @param relayState     the transaction id the IdP echoed back, and the ONLY thing that
	 *                       decides the institution - a client-supplied institutionId is never
	 *                       consulted here or anywhere downstream
	 * @throws ApiException 401 if the authentication carries no assertion, or the transaction is
	 *                      unknown, already used or expired; 403 if the identity holds no
	 *                      membership at that institution
	 */
	public SamlLoginResult complete(Authentication authentication, String relayState) {
		Saml2ResponseAssertionAccessor assertion = assertionOf(authentication);
		Institution institution = institutionFor(relayState);
		TnfUser user = userMapper.map(assertion, institution.institutionId());

		// The token is minted from the mapped user and nothing else. Note the order: a user we
		// could not map never reaches this line, so a failed mapping cannot produce a token.
		IssuedToken token = tokenService.issue(user);

		return new SamlLoginResult(token.token(), token.expiresAt(),
				clock.instant().truncatedTo(ChronoUnit.SECONDS), institution,
				assertion.getNameId(), user);
	}

	/**
	 * The institution is recovered from a transaction we opened, never from the assertion and
	 * never from the request. One IdP serves every institution, so the assertion cannot tell us
	 * which one this is - and if the client could, it could pick any of them.
	 */
	private Institution institutionFor(String relayState) {
		AuthTransaction transaction = transactions.consume(relayState)
				.orElseThrow(() -> new ApiException(ErrorCode.SAML_AUTHENTICATION_FAILED,
						"This sign-in could not be matched to a transaction we started."));

		InstitutionRef institutionRef = institutions.find(transaction.institutionId())
				.orElseThrow(() -> new ApiException(ErrorCode.SAML_AUTHENTICATION_FAILED,
						"The institution this sign-in was started for no longer exists."));
		return new Institution(institutionRef.institutionId(), institutionRef.name());
	}

	private Saml2ResponseAssertionAccessor assertionOf(Authentication authentication) {
		if (authentication instanceof Saml2AssertionAuthentication saml2) {
			return saml2.getCredentials();
		}
		throw new ApiException(ErrorCode.SAML_AUTHENTICATION_FAILED,
				"This sign-in did not produce a SAML assertion.");
	}

	/**
	 * What a completed sign-in produced.
	 *
	 * <p>{@code token} and {@code expiresAt} are the API Reference's sign-in envelope, now that
	 * TokenService exists. {@code institution} and {@code samlSubject} are prototype evidence
	 * that the right transaction and the right identity met - they would come out before this
	 * is shown to a real client.
	 */
	public record SamlLoginResult(String token, Instant expiresAt, Instant serverTime,
			Institution institution, String samlSubject, TnfUser user) {
	}
}
