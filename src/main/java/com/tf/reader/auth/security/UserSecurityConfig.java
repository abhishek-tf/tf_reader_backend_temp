package com.tf.reader.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

import com.tf.reader.auth.saml.SamlAuthenticationFailureHandler;
import com.tf.reader.auth.saml.SamlAuthenticationSuccessHandler;

/**
 * The Service Provider side of the SAML integration.
 *
 * <p><b>One chain only: the SAML leg.</b> The API surface ({@code /api/v1/**}) is protected by
 * {@code common.security.SecurityConfig}, which declares the app resource server, wires
 * {@code TnfJwtValidator} through {@code jwtDecoder}, and permits the public auth routes
 * ({@code /api/v1/auth/saml/start}, {@code /api/v1/auth/oidc/*}, etc.) centrally. Having the
 * auth module define its own secondary chain over {@code /api/v1/auth/**} created a silent
 * coupling: the auth chain's permit-list was the authoritative list of public routes, but only
 * because it ran before the common chain. Moving the permits into {@code SecurityConfig} makes
 * the dependency explicit and removes a duplicate resource-server configuration.
 *
 * <p><b>The SAML leg is stateful</b> because {@code InResponseTo} validation needs the outbound
 * AuthnRequest held in a session; the API is stateless. Keeping them in one chain is what lets
 * the JSESSIONID left behind by sign-in authenticate {@code /api/**} with no bearer token, so
 * they remain separate here.
 *
 * <p>Endpoints Spring Security contributes, at their defaults:
 * <ul>
 * <li>{@code GET /saml2/authenticate?registrationId=tf-reader} — builds the AuthnRequest</li>
 * <li>{@code POST /login/saml2/sso/tf-reader} — the ACS</li>
 * </ul>
 */
@Configuration
public class UserSecurityConfig {

	/**
	 * The query parameter carrying our opaque transaction id into the SAML flow. Read on the
	 * way out, echoed back by the IdP as RelayState.
	 */
	public static final String AUTH_TRANSACTION_PARAM = "authTxn";

	/** The only paths that may authenticate from an HTTP session. Both belong to the SAML leg. */
	static final String[] SAML_PATHS = { "/saml2/**", "/login/saml2/**" };

	/**
	 * The SAML leg, and the only stateful chain in the application.
	 *
	 * <p><b>Why it is separate.</b> This chain needs a session: Spring Security stores the
	 * outbound AuthnRequest in it so the returning response can be checked against it, and
	 * without that there is no {@code InResponseTo} validation and the flow accepts unsolicited
	 * assertions. But a session that can authenticate is a second credential, and Spring Security
	 * persists the SAML authentication into it at the ACS - so with one shared chain, the
	 * JSESSIONID left over from sign-in authenticates every {@code /api/**} route with no bearer
	 * token at all, and the JWT boundary is bypassed entirely. Splitting the chains means the
	 * session exists only where it is needed and reaches nothing else.
	 *
	 * <p>Ordered first so these two paths are matched here rather than by the API chain.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain samlSignInFilterChain(HttpSecurity http,
			SamlAuthenticationSuccessHandler successHandler,
			SamlAuthenticationFailureHandler failureHandler,
			Saml2AuthenticationRequestResolver authenticationRequestResolver) throws Exception {
		return http
				.securityMatcher(SAML_PATHS)
				// The ACS is a cross-site form POST from the IdP, which is exactly what CSRF
				// protection blocks. Nothing here is a cookie-authorised state change.
				.csrf(csrf -> csrf.disable())
				// Sign-in is how a caller obtains a credential; it cannot require one.
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.saml2Login(saml2 -> saml2
						.authenticationRequestResolver(authenticationRequestResolver)
						.successHandler(successHandler)
						.failureHandler(failureHandler))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.build();
	}

	/**
	 * Puts our transaction id into RelayState.
	 *
	 * <p>Spring Security's default resolver invents a random UUID for RelayState. We replace it
	 * with the id issued by {@code /auth/saml/start}, which is what carries the chosen
	 * institution across the redirect without trusting the client for it.
	 */
	@Bean
	Saml2AuthenticationRequestResolver authenticationRequestResolver(
			RelyingPartyRegistrationRepository registrations) {
		OpenSaml5AuthenticationRequestResolver resolver =
				new OpenSaml5AuthenticationRequestResolver(registrations);
		resolver.setRelayStateResolver(request -> request.getParameter(AUTH_TRANSACTION_PARAM));
		return resolver;
	}
}
