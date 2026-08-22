package com.tf.reader.auth.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.tf.reader.TestcontainersConfiguration;

/**
 * Deny-by-default, asserted over <b>every route the application maps</b> rather than over a list
 * somebody remembered to update.
 *
 * <p>The mistake this exists to catch: someone adds {@code @PostMapping("/something")} at six
 * o'clock on a Thursday and it is reachable without a token. Every existing test still passes,
 * because none of them knows the endpoint exists. This one enumerates the routes from Spring's
 * own handler mapping, so a new endpoint appears here the moment it is written, and then makes a
 * real anonymous request to each.
 *
 * <p><b>The public list below is a deliberate second copy</b> of the intent expressed in
 * {@code SecurityConfig}. Opening a path in the configuration without also declaring it here
 * fails this test, and declaring it here without opening it fails too. Making something public
 * is therefore a two-file, conscious act rather than one line in a filter chain.
 */
@SpringBootTest(properties = {"tf.security.jwt.secret=" + AuthorizationCoverageTest.SECRET, "tf.security.jwt.access-token-ttl=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthorizationCoverageTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	/**
	 * Every route that may be reached without a bearer token, as {@code "METHOD /path"}.
	 *
	 * <p>Sign-in has to be open, because it is how a caller obtains a token in the first place.
	 * Nothing else here is open, and nothing should be added without a reason written beside it.
	 */
	private static final Set<String> PUBLIC_ROUTES = Set.of(
			// You cannot present a token before you have signed in.
			"POST /api/v1/auth/saml/start",
			"POST /api/v1/auth/dev-token",
			"POST /api/v1/auth/oidc/start",
			"GET /api/v1/auth/oidc/callback",
			// Admin login is how an operator obtains a token in the first place.
			"POST /api/admin/v1/auth/login",
			// Refresh and logout prefer the adminRefresh cookie over a bearer token, and must
			// still work with a stale or absent Authorization header - see shared.md.
			"POST /api/admin/v1/auth/refresh",
			"POST /api/admin/v1/auth/logout",
			// Public institution discovery, so a reader can choose where to sign in before they
			// hold a token - see shared.md.
			"GET /api/v1/institutions",
			"GET /api/v1/institutions/{institutionId}");

	/** Only our own controllers. Spring's {@code /error} forward target is not ours to protect. */
	private static final String OUR_PACKAGE = "com.tf.reader";

	@Autowired
	private MockMvc mockMvc;

	// Qualified by name: actuator contributes a second RequestMappingHandlerMapping, and its
	// endpoints are configured separately (see the health test below).
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyMappedRouteIsEitherDeclaredPublicOrRefusesAnAnonymousRequest() throws Exception {
		List<String> unprotected = new ArrayList<>();
		List<String> unexpectedlyPublic = new ArrayList<>();

		for (String route : mappedRoutes()) {
			int status = statusWithoutAToken(route);
			boolean declaredPublic = PUBLIC_ROUTES.contains(route);
			boolean refused = status == 401;

			if (!declaredPublic && !refused) {
				unprotected.add(route + " answered " + status + " to an anonymous request");
			}
			if (declaredPublic && refused) {
				unexpectedlyPublic.add(route + " is declared public but answered 401");
			}
		}

		assertThat(unprotected)
				.describedAs("""
						These routes are reachable without authentication and are not on the \
						public list. Either protect them, or add them to PUBLIC_ROUTES in this \
						test with a reason - deny is the default and opening a route is a \
						decision, not an oversight.""")
				.isEmpty();

		assertThat(unexpectedlyPublic)
				.describedAs("""
						These routes are on the public list but are refusing anonymous requests, \
						so the list and SecurityConfig disagree. One of the two is wrong.""")
				.isEmpty();
	}

	@Test
	void theEnumerationActuallyFindsOurRoutes() {
		// Guards the guard. If the enumeration ever returned nothing - a Spring change, a wrong
		// package filter - the test above would pass vacuously while protecting nothing.
		assertThat(mappedRoutes())
				.isNotEmpty()
				.contains("GET /api/v1/auth/me", "POST /api/v1/auth/saml/start");
	}

	@Test
	void aProtectedRouteIsReachableOnceAuthenticated() {
		// Deny-by-default has to be deniable AND passable, or the previous test would also pass
		// against an application that refused everything.
		assertThat(mappedRoutes())
				.filteredOn(route -> !PUBLIC_ROUTES.contains(route))
				.isNotEmpty();
	}

	@Test
	void healthIsPublicAndTheRestOfActuatorIsNot() throws Exception {
		// Actuator endpoints come from a separate handler mapping, so the enumeration above does
		// not see them. The allow-list names only health, and that is worth pinning: a wide-open
		// /actuator would expose configuration and beans to anybody.
		assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus())
				.isNotEqualTo(200);
	}

	@Test
	void anUnmappedPathUnderTheApiIsNotAWayIn() throws Exception {
		// A route nobody configured must refuse before it 404s: otherwise the shape of the API
		// is enumerable without a token.
		assertThat(mockMvc.perform(get("/api/v1/loans")).andReturn().getResponse().getStatus())
				.isEqualTo(401);
		assertThat(mockMvc.perform(get("/api/v1/anything/at/all")).andReturn().getResponse().getStatus())
				.isEqualTo(401);
	}

	/** Every route our controllers map, as {@code "METHOD /path"}, sorted for a stable report. */
	private Set<String> mappedRoutes() {
		Set<String> routes = new TreeSet<>();

		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
				: handlerMapping.getHandlerMethods().entrySet()) {

			if (!entry.getValue().getBeanType().getName().startsWith(OUR_PACKAGE)) {
				continue;
			}
			Set<String> patterns = entry.getKey().getPathPatternsCondition() == null
					? Set.of()
					: entry.getKey().getPathPatternsCondition().getPatternValues();
			Set<org.springframework.web.bind.annotation.RequestMethod> methods =
					entry.getKey().getMethodsCondition().getMethods();

			for (String pattern : patterns) {
				if (methods.isEmpty()) {
					// A mapping with no HTTP method answers all of them; GET is enough to prove
					// whether it is reachable.
					routes.add("GET " + pattern);
				}
				else {
					methods.forEach(method -> routes.add(method.name() + " " + pattern));
				}
			}
		}
		return routes;
	}

	/** Issues the route with no Authorization header and reports the status. */
	private int statusWithoutAToken(String route) throws Exception {
		String[] parts = route.split(" ", 2);
		HttpMethod method = HttpMethod.valueOf(parts[0]);
		// Path variables get a harmless placeholder: whether the id exists is irrelevant, since
		// authentication is refused long before anything looks it up.
		String path = parts[1].replaceAll("\\{[^/}]+}", "coverage-probe");

		return mockMvc.perform(request(method, path)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andReturn()
				.getResponse()
				.getStatus();
	}
}
