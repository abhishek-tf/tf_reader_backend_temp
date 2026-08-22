package com.tf.reader.auth.oidc.mock.config;

import com.tf.reader.auth.oidc.mock.controller.MockOidcController;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Switches the entire mock provider on, or - by default - leaves it out of the application.
 *
 * <p><b>Nothing in this package exists unless {@code mock-oidc.enabled} is explicitly true.</b>
 * Every class carries {@link MockOidcComponent} - or, for the controller, the same condition
 * directly - so "the mock is off" means its beans and its endpoints <b>do not exist</b>, rather
 * than existing and relying on nobody calling them. This class adds the two things that are not
 * components: the filter chain that opens the provider's paths, and the optional second port.
 *
 * <p><b>Default off, and it must stay that way.</b> A mock identity provider is a machine for
 * minting identities; one that could be switched on by forgetting to switch it off is a hole,
 * not a convenience. {@code SecurityArchitectureTest} asserts this condition is still here.
 */
@Configuration
@EnableConfigurationProperties(MockOidcProperties.class)
@ConditionalOnProperty(prefix = "mock-oidc", name = "enabled", havingValue = "true")
public class MockOidcConfig {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MockOidcConfig.class);

	/** The provider's paths. Everything under them belongs to the mock, not to the API. */
	static final String[] MOCK_PATHS = {
			MockOidcController.DISCOVERY_PATH, "/oauth2/**" };

	public MockOidcConfig(MockOidcProperties properties) {
		// Loud on purpose. This line appearing in a production log is an incident, and it should
		// be greppable without knowing what to grep for.
		log.warn("MOCK OIDC PROVIDER IS ENABLED at {} - local development only, never a real "
				+ "environment", properties.issuer());
	}

	/**
	 * The mock's own filter chain, ahead of the application's.
	 *
	 * <p>Its endpoints have to be reachable without a token - a provider that demanded one of our
	 * tokens before it would issue an identity would be a circle - so they are matched here and
	 * permitted, rather than being added to the API chain's allow-list. That distinction matters:
	 * with the mock disabled these paths are not open, they simply do not exist, and the
	 * application's own allow-list stays a list of the application's own public routes.
	 *
	 * <p>Stateless and CSRF-disabled. The consent form is a POST, but there is no cookie session
	 * behind it and nothing to forge on behalf of: the mock authenticates one hardcoded user and
	 * holds no state a browser could be tricked into changing.
	 */
	@Bean
	@Order(0)
	SecurityFilterChain mockOidcFilterChain(HttpSecurity http) throws Exception {
		return http
				.securityMatcher(MOCK_PATHS)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.build();
	}

	/**
	 * An extra HTTP port, so the mock has a genuinely different origin from the application.
	 *
	 * <p><b>Optional, and unset in tests on purpose.</b> With {@code mock-oidc.port} configured -
	 * 9000, locally - the provider answers on its own port and the demo looks like what it is
	 * pretending to be: the browser is redirected to another server, and the backend calls that
	 * other server's token endpoint. With it unset, the same handlers answer on the application's
	 * port and a test needs no fixed port to be free, which is what keeps the suite hermetic and
	 * parallel-safe.
	 *
	 * <p>Both connectors serve the same application, so nothing about routing or security changes
	 * with the port - the paths are what the filter chain matches on. The only thing that must
	 * agree is {@code mock-oidc.issuer} and {@code tnf.auth.oidc.*}, which have to name whichever
	 * port is actually in use, or the client's issuer check will refuse every token the mock
	 * mints. That is the check working, not a bug.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "mock-oidc", name = "port")
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> mockOidcConnector(
			MockOidcProperties properties) {

		return factory -> {
			Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
			connector.setPort(properties.port());
			// addAdditionalConnectors, not addAdditionalTomcatConnectors: Boot 4 moved the Tomcat
			// factory to org.springframework.boot.tomcat and renamed this along the way.
			factory.addAdditionalConnectors(connector);
			log.info("Mock OIDC provider will also listen on port {}", properties.port());
		};
	}
}
