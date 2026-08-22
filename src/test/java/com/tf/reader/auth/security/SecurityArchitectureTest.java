package com.tf.reader.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The architectural rules that keep the security model honest, checked against the source.
 *
 * <p>Each rule here is something that <b>cannot</b> be caught by testing behaviour: the
 * application would still answer correctly under HTTP while having quietly moved a security
 * decision somewhere it can be bypassed. They are the boundaries the work-allocation document
 * describes, expressed as assertions.
 *
 * <p>Scans {@code src/main/java} directly rather than adding ArchUnit - the document assigns the
 * ArchUnit contract rules to another module, and these five rules do not justify a dependency.
 */
class SecurityArchitectureTest {

	private static final Path MAIN = Path.of("src/main/java");

	@Test
	void onlyTheAuthenticationBoundaryTouchesTheSecurityContext() throws IOException {
		// A service that reaches for ambient identity works under HTTP and silently authorizes
		// nothing on a scheduled thread, where there is no request and no context at all. Identity
		// is passed as a parameter everywhere else.
		//
		// admin/security is excluded too: AdminScopeAuthorizer is wokay's own sanctioned identity
		// boundary for the admin console, the same job auth/security does for the reader app, just
		// owned by a different module.
		List<String> offenders = filesContaining(line -> line.contains("SecurityContextHolder"),
				path -> !path.contains("/auth/security/") && !path.contains("/admin/security/"));

		assertThat(offenders)
				.describedAs("SecurityContextHolder may only appear at the authentication boundary")
				.isEmpty();
	}

	@Test
	void nothingOutsideTheTokenAndSecurityPackagesParsesAToken() throws IOException {
		// CurrentUser is built once, at the boundary. A second parser is a second set of rules
		// about what a valid token is, and they will diverge.
		//
		// common/security and admin/service/AdminTokenService.java are excluded: wokay's admin
		// console issues and verifies its own tf-admin/tf-app tokens, a second, deliberately
		// separate token system from this module's reader-facing one, not a second parser of the
		// same token. That system predates this rule.
		// Three named places, and the list is meant to be hard to grow. Each entry is a package
		// whose whole job is handling a token, so a file appearing there is a deliberate decision:
		//   auth.token            - minting OUR token
		//   auth.security         - verifying OUR token
		//   auth.oidc.validation  - verifying the PROVIDER'S ID token
		//   auth.oidc.mock        - the local fixture, which signs tokens as somebody else
		// Everything else in the application reads a CurrentUser and never sees a token at all.
		List<String> offenders = filesContaining(
				line -> line.contains("JwtDecoder") || line.contains("SignedJWT")
						|| line.contains("JWTParser") || line.contains("JwtEncoder"),
				path -> !path.contains("/auth/token/") && !path.contains("/auth/security/")
						&& !path.contains("/common/security/")
						&& !path.endsWith("/admin/service/AdminTokenService.java")
						&& !path.contains("/auth/oidc/validation/")
						&& !path.contains("/auth/oidc/mock/"));

		assertThat(offenders)
				.describedAs("JWTs are encoded and decoded in the four token packages only")
				.isEmpty();
	}

	@Test
	void noOneReadsTheAuthorizationHeaderByHand() throws IOException {
		// Spring Security's bearer filter owns header parsing. A hand-rolled read is how a service
		// ends up trusting a token nobody verified.
		assertThat(filesContaining(line -> line.contains("\"Authorization\"")
				|| line.contains("HttpHeaders.AUTHORIZATION"), path -> true))
				.describedAs("the Authorization header is parsed by the framework, not by us")
				.isEmpty();
	}

	@Test
	void authorizationIsNotDecidedByAnAnnotationOnAController() throws IOException {
		// The service-layer approach is deliberate: a controller-only check is bypassed the moment
		// a second entry point calls the same service. Method security would invite exactly that.
		assertThat(filesContaining(line -> line.contains("@PreAuthorize")
				|| line.contains("@Secured") || line.contains("EnableMethodSecurity"),
				path -> true))
				.describedAs("authorization lives in the service layer, not in annotations")
				.isEmpty();
	}

	@Test
	void controllersDoNotMakeTheAuthorizationDecisionThemselves() throws IOException {
		// Controllers obtain CurrentUser and hand it down. The moment one calls AuthorizationService
		// directly, the guard is attached to an HTTP entry point rather than to the operation.
		// A word boundary, not a substring: the mock identity provider has its own
		// MockOidcAuthorizationService - the OAuth 2.0 authorization ENDPOINT's logic, which has
		// nothing to do with our AuthorizationService - and matching loosely would flag it forever
		// and teach whoever hits it that this rule cries wolf.
		List<String> offenders = filesContaining(
				line -> line.matches(".*(?<![A-Za-z])AuthorizationService\\b.*"),
				path -> path.endsWith("Controller.java"));

		assertThat(offenders)
				.describedAs("a controller calling AuthorizationService puts the check on the "
						+ "entry point instead of the operation it guards")
				.isEmpty();
	}

	/**
	 * The one exemption above, and why it does not weaken the rule.
	 *
	 * <p>{@code auth.oidc.mock} signs JWTs - it has to, because it is a local stand-in for an
	 * identity provider and an ID token that is not really signed cannot exercise the relying
	 * party's signature check. The rule it is exempt from is about <b>the application having one
	 * place that decides what a valid token is</b>; the mock decides nothing, it produces somebody
	 * else's tokens for us to validate. Our own validation still lives only in {@code auth.token}
	 * and {@code auth.security}, which the assertion above still enforces for every other file.
	 *
	 * <p>The exemption is safe only because the mock cannot run outside local development, so that
	 * is asserted here rather than assumed - and asserted per class, so a new mock component that
	 * forgets its condition fails the build instead of shipping switched on.
	 */
	@Test
	void everyPartOfTheMockIdentityProviderIsSwitchedOffUnlessAskedFor() throws IOException {
		List<String> unconditional = new ArrayList<>();

		for (Path file : sourceFiles()) {
			String path = file.toString().replace('\\', '/');
			if (!path.contains("/auth/oidc/mock/")) {
				continue;
			}
			String source = Files.readString(file);
			boolean conditional = source.contains("@ConditionalOnProperty")
					|| source.contains("@MockOidcComponent")
					// Records holding configuration are inert: they are only ever constructed by
					// the conditional configuration that binds them.
					|| source.contains("public record ")
					|| source.contains("public @interface ");

			if (!conditional) {
				unconditional.add(path);
			}
		}

		assertThat(unconditional)
				.describedAs("""
						Every class in the mock identity provider must be conditional on \
						mock-oidc.enabled - annotate it @MockOidcComponent. A mock provider mints \
						identities for arbitrary users; one that can be switched on by forgetting \
						to switch it off is a hole, not a convenience.""")
				.isEmpty();
	}

	@Test
	void theMockIdentityProviderIsOffByDefault() throws IOException {
		// The configuration half of the rule above. A default of true here would make every one of
		// those conditions pass everywhere, including production.
		assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
				.describedAs("application.yml must ship mock-oidc disabled")
				.contains("mock-oidc:")
				.contains("enabled: false");
	}

	@Test
	void theTwoAuthenticationMechanismsDoNotDependOnEachOther() throws IOException {
		// SAML is B2B, OIDC is B2C, and they are parallel by design: either one must be
		// understandable, testable and replaceable without reading the other. A single import
		// across the line is how "parallel" quietly becomes "coupled", and the day B2C moves to a
		// real Azure tenant is the day that coupling is discovered.
		//
		// What they DO share is deliberate and sits below both: TnfUser, TokenService, CurrentUser,
		// AuthorizationService and the error contract. That convergence is the architecture; a
		// sideways dependency between the legs is not.
		List<String> samlToOidc = filesContaining(
				line -> line.contains("com.tf.reader.auth.oidc"),
				path -> path.contains("/auth/saml/"));

		List<String> oidcToSaml = filesContaining(
				line -> line.contains("com.tf.reader.auth.saml"),
				path -> path.contains("/auth/oidc/"));

		assertThat(samlToOidc)
				.describedAs("the SAML leg must not reference OIDC")
				.isEmpty();
		assertThat(oidcToSaml)
				.describedAs("the OIDC leg must not reference SAML")
				.isEmpty();
	}

	@Test
	void neitherMechanismMintsOrValidatesItsOwnIdentity() throws IOException {
		// Both legs must end at the shared pipeline rather than growing a private copy of it. A
		// second TokenService or a hand-built CurrentUser inside one leg would mean two answers to
		// "who is this and what may they do", and only one of them would get the next security fix.
		List<String> offenders = filesContaining(
				line -> line.contains("new CurrentUser(")
						|| line.contains("implements TokenService")
						|| line.contains("class JwtTokenService"),
				path -> path.contains("/auth/saml/") || path.contains("/auth/oidc/"));

		assertThat(offenders)
				.describedAs("""
						SAML and OIDC map an external identity to a TnfUser and hand it to the \
						shared TokenService. Building a CurrentUser or a token implementation \
						inside a mechanism forks the identity model.""")
				.isEmpty();
	}

	@Test
	void theScanActuallyReadsSource() throws IOException {
		// Guards the guard: if the path were wrong, every rule above would pass vacuously.
		assertThat(filesContaining("CurrentUser")).isNotEmpty();
		assertThat(sourceFiles()).hasSizeGreaterThan(15);
	}

	/** Files mentioning a literal, excluding the authentication boundary itself. */
	private List<String> filesContaining(String needle) throws IOException {
		return filesContaining(line -> line.contains(needle),
				path -> !path.contains("/auth/security/"));
	}

	private List<String> filesContaining(Predicate<String> lineMatches, Predicate<String> pathMatches)
			throws IOException {
		List<String> hits = new ArrayList<>();
		for (Path file : sourceFiles()) {
			String path = file.toString().replace('\\', '/');
			if (!pathMatches.test(path)) {
				continue;
			}
			for (String line : Files.readAllLines(file)) {
				String code = line.strip();
				// Comments and javadoc name these types when explaining why they are not used.
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
					continue;
				}
				if (lineMatches.test(code)) {
					hits.add(path + " → " + code);
					break;
				}
			}
		}
		return hits;
	}

	private List<Path> sourceFiles() throws IOException {
		try (Stream<Path> walk = Files.walk(MAIN)) {
			return walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}
}
