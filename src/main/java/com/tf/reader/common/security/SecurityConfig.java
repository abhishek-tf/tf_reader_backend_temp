package com.tf.reader.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.tf.reader.admin.security.AdminJwtAuthenticationConverter;
import com.tf.reader.auth.security.CurrentUserJwtConverter;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP security. Chains run most specific to least and the last denies everything, so a new endpoint
 * is unreachable until deliberately placed under a chain.
 *
 * <p>Each surface gets its own resource server and decoder, so audience separation is enforced by the
 * filter chain rather than by anything a controller has to remember.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

	static final String LOGIN_PATH = "/api/admin/v1/auth/login";
	static final String REFRESH_PATH = "/api/admin/v1/auth/refresh";
	static final String LOGOUT_PATH = "/api/admin/v1/auth/logout";

	/**
	 * The app-side prefixes, taken from the API contract rather than invented. Everything under them
	 * needs a {@code tf-app} token unless one of the public matchers below claims it first.
	 */
	static final String APP_API_PATHS = "/api/v1/**";
	static final String APP_OPDS_PATHS = "/opds/v1/**";

	/**
	 * The app paths the contract marks {@code security: []}. Public institution discovery is how a
	 * reader chooses where to sign in, and the public OPDS feeds are open-access browsing, so both have
	 * to work before anyone holds a token at all.
	 */
	static final String PUBLIC_INSTITUTIONS_PATH = "/api/v1/institutions";

	/** One segment only, so a later {@code /{id}/something-private} does not inherit public access. */
	static final String PUBLIC_INSTITUTION_PATH = "/api/v1/institutions/*";

	static final String PUBLIC_OPDS_PATHS = "/opds/v1/public/**";

	private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
	private final ProblemAccessDeniedHandler accessDeniedHandler;

	public SecurityConfig(ProblemAuthenticationEntryPoint authenticationEntryPoint,
			ProblemAccessDeniedHandler accessDeniedHandler) {
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * Login, refresh and logout, the only three public admin endpoints. No resource server is attached:
	 * a stale {@code Authorization} header must not stop a caller logging in, refreshing or logging out.
	 *
	 * <p>Login carries its credential in the request body. Refresh and logout now prefer the
	 * {@code adminRefresh} cookie, and a cookie is attached by the browser whether or not the request
	 * was intended, which is what makes them CSRF targets. So this is the one chain with CSRF enabled.
	 *
	 * <p>Login is exempt. It holds no cookie authority, so a forged login only signs the victim into an
	 * account the attacker already controls, and requiring a token there would mean fetching one before
	 * anybody can sign in. {@code SameSite=Strict} on the cookie is the other half of the defence.
	 *
	 * <p>The token repository is readable by script on purpose: the console has to echo it back in
	 * {@code X-XSRF-TOKEN}. That is safe because an attacker on another origin cannot read it, and one
	 * with script on this origin has already won. {@link CsrfCookieFilter} makes sure the login response
	 * carries one, so the console has a token in hand before its first refresh.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain publicAdminAuthFilterChain(HttpSecurity http) throws Exception {
		CsrfTokenRequestAttributeHandler csrfTokenHandler = new CsrfTokenRequestAttributeHandler();
		csrfTokenHandler.setCsrfRequestAttributeName(null);
		CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();

		http.securityMatcher(LOGIN_PATH, REFRESH_PATH, LOGOUT_PATH)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.POST, LOGIN_PATH, REFRESH_PATH, LOGOUT_PATH).permitAll()
						.anyRequest().denyAll());

		return stateless(http)
				.csrf(csrf -> csrf
						.csrfTokenRepository(csrfTokenRepository)
						.csrfTokenRequestHandler(csrfTokenHandler)
						.ignoringRequestMatchers(LOGIN_PATH))
				.addFilterAfter(new CsrfCookieFilter(csrfTokenRepository), CsrfFilter.class)
				.build();
	}

	/**
	 * Guarantees an {@code XSRF-TOKEN} cookie on any request to this chain that arrives without one.
	 *
	 * <p>Spring materialises the token only on a request it actually checks, and login is exempt. It
	 * happens to write one on some login responses and not others, and "sometimes" is not something the
	 * console can build on: without a token in hand its first refresh would 403 purely to collect one.
	 */
	private static final class CsrfCookieFilter extends OncePerRequestFilter {

		private final CsrfTokenRepository csrfTokenRepository;

		private CsrfCookieFilter(CsrfTokenRepository csrfTokenRepository) {
			this.csrfTokenRepository = csrfTokenRepository;
		}

		/** One token per request, not one per dispatch: an error dispatch must not mint a second. */
		@Override
		protected boolean shouldNotFilterErrorDispatch() {
			return true;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
				FilterChain filterChain) throws ServletException, IOException {

			if (this.csrfTokenRepository.loadToken(request) == null) {
				CsrfToken token = this.csrfTokenRepository.generateToken(request);
				this.csrfTokenRepository.saveToken(token, request, response);
			}
			filterChain.doFilter(request, response);
		}

	}

	/** Liveness/readiness only. Every other actuator endpoint falls through to the deny-all chain. */
	@Bean
	@Order(2)
	SecurityFilterChain actuatorHealthFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/actuator/health", "/actuator/health/**")
				.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
		return stateless(http).build();
	}

	/**
	 * The app paths that carry no token: public institution discovery and the open-access feeds.
	 *
	 * <p>Ordered ahead of the {@code tf-app} chain because only the first chain whose matcher matches
	 * ever runs, and these paths sit underneath its prefixes. Without this chain, binding the app
	 * surface to {@code tf-app} would make team1's institution picker and anonymous open-access
	 * browsing require a token the caller cannot have yet.
	 *
	 * <p>No resource server is attached, for the same reason as the admin auth chain: a stale token
	 * left in an {@code Authorization} header must not break a request that needs no token, so a reader
	 * whose app token has expired can still browse open access.
	 */
	@Bean
	@Order(3)
	SecurityFilterChain publicAppFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(PUBLIC_INSTITUTIONS_PATH, PUBLIC_INSTITUTION_PATH, PUBLIC_OPDS_PATHS)
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, PUBLIC_INSTITUTIONS_PATH, PUBLIC_INSTITUTION_PATH,
								PUBLIC_OPDS_PATHS)
						.permitAll()
						.anyRequest().denyAll());
		return stateless(http).build();
	}

	/** Admin API. Requires a valid, session-backed {@code tf-admin} access token. */
	@Bean
	@Order(4)
	SecurityFilterChain adminApiFilterChain(HttpSecurity http,
			@Qualifier(JwtConfig.ADMIN_ACCESS_TOKEN_DECODER) JwtDecoder adminAccessTokenDecoder) throws Exception {

		http.securityMatcher("/api/admin/**")
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(this.authenticationEntryPoint)
						.accessDeniedHandler(this.accessDeniedHandler)
						.jwt(jwt -> jwt
								.decoder(adminAccessTokenDecoder)
								.jwtAuthenticationConverter(new AdminJwtAuthenticationConverter())));
		return stateless(http).build();
	}

	/**
	 * Reader app API: the institution-scoped OPDS feeds and the catalogue batch endpoint.
	 *
	 * <p>Most of these are not written yet. The chain still binds the surface to its own audience now,
	 * so an admin or refresh token presented here is rejected during decoding, before routing, and an
	 * endpoint another team adds later inherits that without anyone having to remember.
	 */
	@Bean
	@Order(5)
	SecurityFilterChain appApiFilterChain(HttpSecurity http,
			@Qualifier("jwtDecoder") JwtDecoder jwtDecoder,
			CurrentUserJwtConverter currentUserJwtConverter) throws Exception {

		http.securityMatcher(APP_API_PATHS, APP_OPDS_PATHS)
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						// ── Public auth endpoints ──
						// Sign-in start points cannot require a token — a token is what sign-in
						// produces. The OIDC callback is entered by a browser redirect from the IdP
						// which carries no bearer token and never will.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/saml/start").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/oidc/start").permitAll()
						.requestMatchers(HttpMethod.GET,  "/api/v1/auth/oidc/callback").permitAll()
						// Dev-only convenience token endpoint. No environment guard — see auth audit
						// issue #1; this is a known risk documented separately.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/dev-token").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(this.authenticationEntryPoint)
						.accessDeniedHandler(this.accessDeniedHandler)
						.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(currentUserJwtConverter)));
		return stateless(http).build();
	}

	/** Dev profile only; elsewhere these paths fall through to the deny-all chain. */
	@Bean
	@Order(6)
	@Profile("dev")
	SecurityFilterChain apiDocsFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html",
				"/swagger-ui/**")
				.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
		return stateless(http).build();
	}

	/** Everything not matched above is denied. ERROR dispatches pass so a genuine 404 still renders. */
	@Bean
	@Order(100)
	SecurityFilterChain denyAllFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.anyRequest().denyAll());
		return stateless(http).build();
	}

	/**
	 * Stateless JSON API: no sessions, no browser login flows. CSRF is off by default because a bearer
	 * header is not something a browser attaches by itself. The one chain that reads a cookie turns it
	 * back on after calling this.
	 */
	private HttpSecurity stateless(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.anonymous(Customizer.withDefaults())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(this.authenticationEntryPoint)
						.accessDeniedHandler(this.accessDeniedHandler));
	}

}
