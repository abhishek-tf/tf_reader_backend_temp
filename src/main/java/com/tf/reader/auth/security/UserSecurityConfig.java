package com.tf.reader.auth.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

import com.tf.reader.auth.ApiAuthenticationEntryPoint;
import com.tf.reader.auth.saml.SamlAuthenticationFailureHandler;
import com.tf.reader.auth.saml.SamlAuthenticationSuccessHandler;

/**
 * The Service Provider side of the SAML integration, and the filter chains in front of the API.
 *
 * <p><b>Two chains, and the split is a security boundary.</b> The SAML leg is stateful because
 * {@code InResponseTo} validation needs the outbound AuthnRequest held in a session; the API is
 * stateless because a session that can authenticate is a second credential that never passed
 * through {@link com.tf.reader.auth.security.TnfJwtValidator}. Keeping them in one chain is what
 * lets the JSESSIONID left behind by sign-in authenticate {@code /api/**} with no bearer token.
 *
 * <p><b>One registration, every institution.</b> The relying party registration itself is
 * declared in {@code application.yml} under the single id {@code tf-reader}; there is no
 * per-institution registration and no institution anywhere in this class. The institution is
 * business data recovered from our own sign-in transaction, which is what
 * {@link SamlAuthenticationSuccessHandler} does after Spring Security has validated the
 * assertion.
 *
 * <p>Endpoints Spring Security contributes, at their defaults:
 * <ul>
 * <li>{@code GET /saml2/authenticate?registrationId=tf-reader} - builds and signs nothing,
 * redirects to the IdP with the AuthnRequest and our RelayState</li>
 * <li>{@code POST /login/saml2/sso/tf-reader} - the ACS. Validates the assertion signature
 * against the IdP certificate, the audience, the destination, the issuer, the conditions and
 * {@code InResponseTo} against the AuthnRequest held in the session</li>
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
	 * This module's own two endpoints: {@code /api/v1/auth/me} and {@code /api/v1/auth/saml/start}.
	 * Bearer tokens only.
	 *
	 * <p><b>Scoped to {@code /api/v1/auth/**}, not the whole app API.</b> {@code
	 * common.security.SecurityConfig} already binds the rest of {@code /api/v1/**} to its own
	 * audience, including the paths it deliberately leaves public, like {@code
	 * /api/v1/institutions} - a public path still runs its resource server filter for any bearer
	 * token that IS presented, garbage or not, so a wider matcher here would make a stale or
	 * foreign header 401 a path that is supposed to ignore it. Since only the first chain whose
	 * matcher matches ever runs, this chain has to stay out of the way of the rest of the surface.
	 *
	 * <p><b>Stateless on purpose, and it is a security property rather than a performance one.</b>
	 * A stateless chain never reads the HTTP session, so no session - including the one the SAML
	 * leg creates - can authenticate a request here. The only way to hold an identity on this
	 * chain is to present a token that passed signature and claim validation, which is what makes
	 * {@code CurrentUser} the single source of truth about the caller for every module behind this
	 * filter, not just for the ones written today.
	 */
	@Bean
	@Order(2)
	SecurityFilterChain apiFilterChain(HttpSecurity http,
			ApiAuthenticationEntryPoint apiEntryPoint,
			CurrentUserJwtConverter currentUserConverter,
			@Qualifier("jwtDecoder") JwtDecoder jwtDecoder) throws Exception {
		return http
				.securityMatcher("/api/v1/auth/**")
				// The API is bearer-token based: there is no cookie-authorised state-changing
				// endpoint for CSRF protection to protect.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/saml/start").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/dev-token").permitAll()
						// OIDC: start cannot require a token; callback is a browser redirect from IdP
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/oidc/start").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/oidc/callback").permitAll()
						.anyRequest().authenticated())
				// Every request after sign-in presents the JWT that sign-in produced. Spring
				// Security's own bearer-token filter does the header parsing and the decoding, so
				// the only thing of ours in this path is the converter that turns verified claims
				// into a CurrentUser.
				//
				// The entry point is set explicitly: left alone, the resource server installs its
				// own and our JSON refusals would become empty-bodied 401s for exactly the
				// requests that carry a token.
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(apiEntryPoint)
						.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(currentUserConverter)))
				// The app needs a 401 it can act on, not HTML it cannot parse.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(apiEntryPoint))
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
