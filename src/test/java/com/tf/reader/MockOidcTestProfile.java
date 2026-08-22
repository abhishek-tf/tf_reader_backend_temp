package com.tf.reader;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The configuration that switches the local mock OIDC provider on for a test, and points the
 * relying party at it.
 *
 * <p>Extend this from any {@code @SpringBootTest} that exercises OIDC. Everything else in the
 * suite inherits {@link ContainerisedInfrastructure} directly and therefore runs with the mock
 * <b>absent</b> - which is the default, and which is what keeps the existing SAML and security
 * tests entirely unaffected by any of this.
 *
 * <p><b>{@code mock-oidc.port} is deliberately not set.</b> Locally the mock listens on its own
 * port (9000) so the demo has a genuinely separate origin; in tests a fixed second port would be
 * a shared resource that has to be free, which makes a suite fail for reasons that have nothing
 * to do with the code. Unset, the same handlers answer on the application's own port and every
 * url below is consistent with that.
 *
 * <p><b>The two secrets are set separately, and to the same value.</b> They are different
 * properties - the provider's copy and the client's - and the local configuration defaults one
 * from the other only as a convenience. Writing them out here keeps it visible that a real check
 * happens between them, and {@code MockOidcProviderTest} sets them apart to prove it.
 */
public abstract class MockOidcTestProfile extends ContainerisedInfrastructure {

	/**
	 * A free port, chosen once per JVM, that the application and the mock both answer on.
	 *
	 * <p><b>Why a real port rather than {@code RANDOM_PORT}.</b> Two hops in this flow are genuine
	 * HTTP calls the application makes to the provider - fetching the JWKS to verify a signature,
	 * and exchanging the authorization code - so a test that only has MockMvc cannot exercise
	 * either. But the issuer and the endpoint urls have to be <em>configured before the context
	 * starts</em>, and a random port is only known after, so the port is picked here and handed to
	 * the server rather than the other way round.
	 *
	 * <p>Tests that only need MockMvc ({@code MockOidcProviderTest}) ignore all this; the port is
	 * simply never listened on for them.
	 */
	private static final int PORT = freePort();

	/**
	 * The provider's origin, and the {@code iss} it stamps into every token.
	 *
	 * <p>The client's expected issuer is set to the same value, which is the point: if they ever
	 * disagree, every sign-in fails on the issuer check - and that is the check working, not a
	 * broken test.
	 */
	public static final String ISSUER = "http://localhost:" + PORT;

	public static final String CLIENT_ID = "reader-local";

	public static final String CLIENT_SECRET = "test-only-not-a-real-secret";

	public static final String REDIRECT_URI = ISSUER + "/api/v1/auth/oidc/callback";

	/** The application's base url, which in these tests is the same origin as the provider. */
	public static String baseUrl() {
		return ISSUER;
	}

	/**
	 * A port nothing is listening on, as of a moment ago.
	 *
	 * <p>Technically a race - something else could take it between the socket closing and Tomcat
	 * binding - but it is the same race every "find a free port" helper runs, and the alternative
	 * (a fixed port) is not a race, it is a guaranteed collision the day two builds run at once.
	 */
	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException noPortAvailable) {
			throw new IllegalStateException("could not find a free port for the mock provider",
					noPortAvailable);
		}
	}

	/** Stand-in values for tests that drive the provider directly, without a transaction. */
	public static final String STATE = "test-state-value";

	public static final String NONCE = "test-nonce-value";

	@DynamicPropertySource
	static void mockOidcProvider(DynamicPropertyRegistry registry) {
		// Honoured by tests declaring webEnvironment = DEFINED_PORT, ignored by MockMvc ones.
		registry.add("server.port", () -> PORT);

		// The provider.
		registry.add("mock-oidc.enabled", () -> true);
		registry.add("mock-oidc.issuer", () -> ISSUER);
		registry.add("mock-oidc.client-id", () -> CLIENT_ID);
		registry.add("mock-oidc.client-secret", () -> CLIENT_SECRET);
		registry.add("mock-oidc.redirect-uri", () -> REDIRECT_URI);

		// The relying party, pointed at it. These are the six values that become a B2C tenant's
		// in production, and nothing else changes.
		registry.add("tnf.auth.oidc.client-id", () -> CLIENT_ID);
		registry.add("tnf.auth.oidc.client-secret", () -> CLIENT_SECRET);
		registry.add("tnf.auth.oidc.issuer", () -> ISSUER);
		registry.add("tnf.auth.oidc.authorization-uri", () -> ISSUER + "/oauth2/authorize");
		registry.add("tnf.auth.oidc.token-uri", () -> ISSUER + "/oauth2/token");
		registry.add("tnf.auth.oidc.jwk-set-uri", () -> ISSUER + "/oauth2/jwks");
		registry.add("tnf.auth.oidc.redirect-uri", () -> REDIRECT_URI);
	}
}
