package com.tf.reader.auth.oidc.client;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.repository.MockUserRepository;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Turns a validated OIDC ID token into a TnF user.
 *
 * <p>The exact counterpart of {@link com.tf.reader.auth.saml.SamlUserMapper}, deliberately: the
 * provider tells us who someone is, the institution comes from the sign-in transaction our own
 * backend opened, and this class is the only place those two facts meet. <b>Both mappers end at
 * the same {@link MockUserRepository} lookup</b>, so a SAML sign-in and an OIDC sign-in for the
 * same person at the same institution resolve to the same {@code userId}. That convergence is
 * the point of having one user store rather than one per protocol, and it is what lets every
 * module behind the filter chain stay ignorant of how anybody signed in.
 *
 * <p>Takes a {@link Jwt} because that is what verification produces - a token whose signature,
 * issuer, audience, expiry and nonce have all been checked. There is no overload taking a raw
 * string, so there is no path by which an unverified token reaches a user lookup.
 *
 * <p><b>What is deliberately NOT read from any claim:</b> roles, collections, user type and
 * institution. Those are this application's authorization model and they come from our own user
 * store. A claim is the provider's statement about identity, not a grant of authority here - and
 * a {@code roles} claim honoured at this line would let anyone who can edit a B2C user flow's
 * output claims, or anyone who can reconfigure the mock, make themselves an administrator of
 * the Reader.
 */
@Component
@EnableConfigurationProperties(OidcProperties.class)
public class OidcUserMapper {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(OidcUserMapper.class);

	private final MockUserRepository users;
	private final OidcProperties.Claims claims;

	public OidcUserMapper(MockUserRepository users, OidcProperties properties) {
		this.users = users;
		this.claims = properties.claims();
	}

	/**
	 * @param idToken       an ID token that has already been fully validated
	 * @param institutionId the institution recovered from the sign-in transaction, never from a
	 *                      claim and never from the client
	 * @throws ApiException 401 if the token carries no email we can identify; 403 if the identity
	 *                      holds no membership at that institution
	 */
	public TnfUser map(Jwt idToken, String institutionId) {
		String email = resolveEmail(idToken);

		TnfUser user = users.find(email, institutionId)
				.orElseThrow(() -> {
					// Authenticated is not provisioned, and the log says which of the two failed -
					// it is the first thing anybody debugging a new tenant needs to know.
					log.warn("OIDC identity authenticated but not provisioned at institution {}",
							institutionId);
					return new ApiException(ErrorCode.USER_NOT_PROVISIONED,
							"This identity holds no membership at institution '" + institutionId + "'.");
				});

		// The user id, not the email: an address is personal data and this line ends up in a log
		// file that outlives the request.
		log.info("OIDC user resolved: {} at {}", user.userId(), institutionId);
		return user;
	}

	/**
	 * The first configured email claim carrying usable text.
	 *
	 * @throws ApiException 401 if none of them do. Refused rather than defaulted: an identity we
	 *                      cannot name is not one we can look a membership up for, and guessing
	 *                      (the subject, say) would look a user up by a value that is not an email
	 *                      address at all
	 */
	String resolveEmail(Jwt idToken) {
		String email = firstUsableClaim(idToken.getClaims(), claims.email());
		if (email == null) {
			// Names which claims were looked for, not what the token contained: the claim set of a
			// real user is not something to write into a response or a log.
			throw new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
					"The ID token carried no email address in any of the claims we read.");
		}
		return email;
	}

	/**
	 * The provider's stable identifier for this user, for the audit trail. Optional: it is
	 * evidence, not identity, so a token without one still signs in.
	 */
	String resolveSubject(Jwt idToken) {
		return firstUsableClaim(idToken.getClaims(), claims.subject());
	}

	/**
	 * Reads a claim that may be a string or a list of them.
	 *
	 * <p>Azure AD B2C emits {@code emails} as a JSON <b>array</b> even when it holds one address,
	 * while {@code email} and {@code preferred_username} - and our mock - are plain strings.
	 * Handling both here is what lets one configuration serve a B2C user flow, the Microsoft
	 * identity platform and the local mock. Anything that is neither is skipped rather than
	 * coerced: {@code String.valueOf} on a map would produce a plausible-looking lookup key out
	 * of nothing.
	 */
	private static String firstUsableClaim(Map<String, Object> allClaims, List<String> candidates) {
		for (String name : candidates) {
			Object value = allClaims.get(name);
			if (value instanceof String text && StringUtils.hasText(text)) {
				return text;
			}
			if (value instanceof List<?> values) {
				for (Object element : values) {
					if (element instanceof String text && StringUtils.hasText(text)) {
						return text;
					}
				}
			}
		}
		return null;
	}
}
