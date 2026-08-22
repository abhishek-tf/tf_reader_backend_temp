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
		List<String> offenders = filesContaining(
				line -> line.contains("JwtDecoder") || line.contains("SignedJWT")
						|| line.contains("JWTParser") || line.contains("JwtEncoder"),
				path -> !path.contains("/auth/token/") && !path.contains("/auth/security/")
						&& !path.contains("/common/security/")
						&& !path.endsWith("/admin/service/AdminTokenService.java"));

		assertThat(offenders)
				.describedAs("JWTs are encoded and decoded in auth.token and auth.security only")
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
		List<String> offenders = filesContaining(
				line -> line.contains("AuthorizationService"),
				path -> path.endsWith("Controller.java"));

		assertThat(offenders)
				.describedAs("a controller calling AuthorizationService puts the check on the "
						+ "entry point instead of the operation it guards")
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
