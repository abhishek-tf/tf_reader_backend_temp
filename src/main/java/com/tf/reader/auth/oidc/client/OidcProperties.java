package com.tf.reader.auth.oidc.client;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the backend needs to be an OpenID Connect relying party, bound from
 * {@code tnf.auth.oidc.*}.
 *
 * <p><b>No provider is named anywhere in this class, and that is the point.</b> The local mock
 * and Azure AD B2C differ only in these values, so swapping one for the other is a configuration
 * change and not a code change. Nothing below has a provider-specific default: the defaults in
 * {@code application.yml} point at the in-process mock because that is what makes the project
 * runnable out of the box, and the {@code b2c} profile document in the same file overrides
 * the same six keys.
 *
 * <p><b>{@link #issuer} is configured separately from the endpoints on purpose.</b> The url a
 * discovery document is fetched <em>from</em> is not necessarily the {@code iss} the provider
 * <em>puts in its tokens</em> — for Azure AD B2C they are reliably different, because the
 * metadata url carries the tenant name and the policy while the issuer carries the directory
 * guid. Deriving one from the other is the mistake that ends with the issuer unchecked.
 *
 * @param clientId         our client id at the provider
 * @param clientSecret     the client secret. From the environment; never committed
 * @param issuer           the exact {@code iss} every ID token must carry
 * @param authorizationUri where the browser is sent to authenticate
 * @param tokenUri         where the backend exchanges the authorization code, server to server
 * @param jwkSetUri        where the provider publishes the keys its ID tokens are signed with
 * @param redirectUri      our callback. Must be registered at the provider, character for
 *                         character, and is never taken from a request
 * @param scopes           requested scopes. {@code openid} is what makes this OIDC rather than
 *                         plain OAuth 2.0 - without it there is no ID token at all
 * @param transactionTtl   how long a sign-in may take before its transaction expires
 * @param claims           which ID token claims we are willing to read
 */
@ConfigurationProperties(prefix = "tnf.auth.oidc")
public record OidcProperties(
		String clientId,
		String clientSecret,
		String issuer,
		String authorizationUri,
		String tokenUri,
		String jwkSetUri,
		String redirectUri,
		List<String> scopes,
		Duration transactionTtl,
		Claims claims) {

	private static final List<String> DEFAULT_SCOPES = List.of("openid", "profile", "email");

	/** Long enough for a human to work through a sign-in page, short enough to be useless later. */
	private static final Duration DEFAULT_TRANSACTION_TTL = Duration.ofMinutes(10);

	public OidcProperties {
		scopes = (scopes == null || scopes.isEmpty()) ? DEFAULT_SCOPES : List.copyOf(scopes);
		transactionTtl = (transactionTtl != null) ? transactionTtl : DEFAULT_TRANSACTION_TTL;
		claims = (claims != null) ? claims : Claims.defaults();
	}

	/** The scopes as the space-delimited string an authorization request carries. */
	public String scopeParameter() {
		return String.join(" ", scopes);
	}

	/** Defaults with one issuer set, for a caller constructing this outside Spring. */
	public static OidcProperties forIssuer(String issuer) {
		return new OidcProperties(null, null, issuer, null, null, null, null, null, null, null);
	}

	/** Defaults with a claim mapping set, for the tests that vary it. */
	public static OidcProperties withClaims(Claims claims) {
		return new OidcProperties(null, null, null, null, null, null, null, null, null, claims);
	}

	/**
	 * Which ID token claims carry the two facts we are willing to read from the provider.
	 *
	 * <p><b>Two, and only two.</b> An email to look a membership up by, and a subject to record
	 * the external identity as. Everything else about a user - their roles, their entitled
	 * collections, their user type and above all their institution - comes from our own user
	 * store, because a claim is an assertion by the identity provider about identity, not a grant
	 * of authority in this application. Anybody able to edit a B2C user flow's output claims must
	 * not be able to mint themselves an ADMIN role here.
	 *
	 * <p><b>Why the email is a list and not one name.</b> There is no single claim every OIDC
	 * provider puts an email address in. Azure AD B2C emits {@code emails} - a JSON <i>array</i>.
	 * Entra External ID, the Microsoft identity platform and our local mock emit {@code email};
	 * work/school accounts often carry only {@code preferred_username} or {@code upn}. The
	 * candidates are ordered and configurable, and the first one carrying usable text wins, so
	 * changing provider or user flow is configuration rather than code.
	 *
	 * @param email   claim names to try for the email address, most preferred first
	 * @param subject claim names to try for the provider's stable identifier for this user.
	 *                {@code oid} before {@code sub}: B2C's {@code sub} is pairwise - a different
	 *                value per application - whereas {@code oid} is the directory object id and is
	 *                the same across every app in the tenant. Our mock emits only {@code sub}
	 */
	public record Claims(List<String> email, List<String> subject) {

		static final List<String> DEFAULT_EMAIL_CLAIMS =
				List.of("emails", "email", "preferred_username", "upn");

		static final List<String> DEFAULT_SUBJECT_CLAIMS = List.of("oid", "sub");

		public Claims {
			// Absent means "the defaults", not "no claims to read" - an empty candidate list would
			// make every sign-in fail with "no email claim", which reads as a broken provider
			// rather than as a missing block that was never meant to be mandatory.
			email = (email == null || email.isEmpty()) ? DEFAULT_EMAIL_CLAIMS : List.copyOf(email);
			subject = (subject == null || subject.isEmpty())
					? DEFAULT_SUBJECT_CLAIMS
					: List.copyOf(subject);
		}

		public static Claims defaults() {
			return new Claims(null, null);
		}
	}
}
