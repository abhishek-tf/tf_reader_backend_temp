package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;

import com.tf.reader.auth.repository.MockInstitutionRepository;
import com.tf.reader.auth.repository.MockUserRepository;
import com.tf.reader.auth.security.TnfJwtValidator;
import com.tf.reader.auth.saml.SamlAuthenticationService.SamlLoginResult;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The join between a validated SAML identity and the institution our backend chose.
 *
 * <p>No servlet, no network, no samlmock.dev - the service deliberately knows nothing about
 * HTTP, which is what makes this testable at this level.
 */
class SamlAuthenticationServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
	private static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private final AuthTransactionStore transactions =
			new AuthTransactionStore(Clock.fixed(NOW, ZoneOffset.UTC));

	private final SamlAuthenticationService service = new SamlAuthenticationService(transactions,
			new MockInstitutionRepository(), new SamlUserMapper(new MockUserRepository()),
			JwtTokenService.forTest(SECRET, java.time.Duration.ofHours(1),
					Clock.fixed(NOW, ZoneOffset.UTC)),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void completesASignInForTheInstitutionTheTransactionWasOpenedFor() {
		AuthTransaction transaction = transactions.open("inst_imperial");

		SamlLoginResult result = service.complete(samlAuthentication("john.doe@example.com"),
				transaction.id());

		assertThat(result.institution().institutionId()).isEqualTo("inst_imperial");
		assertThat(result.institution().name()).isEqualTo("Imperial College");
		assertThat(result.samlSubject()).isEqualTo("john.doe@example.com");
		assertThat(result.user().userId()).isEqualTo("usr_6712ab");
		assertThat(result.serverTime()).isEqualTo(NOW);
	}

	@Test
	void theSameIdentityCompletesAsADifferentUserAtAnotherInstitution() {
		// Same IdP, same assertion, different transaction - the acceptance criterion for
		// "one SAML integration, many business institutions".
		Authentication sameIdentity = samlAuthentication("john.doe@example.com");

		SamlLoginResult imperial =
				service.complete(sameIdentity, transactions.open("inst_imperial").id());
		SamlLoginResult dsu = service.complete(sameIdentity, transactions.open("inst_dsu").id());

		assertThat(imperial.user().userId()).isEqualTo("usr_6712ab");
		assertThat(imperial.user().institutionId()).isEqualTo("inst_imperial");
		assertThat(dsu.user().userId()).isEqualTo("usr_8c14de");
		assertThat(dsu.user().institutionId()).isEqualTo("inst_dsu");
		assertThat(imperial.samlSubject()).isEqualTo(dsu.samlSubject());
	}

	@Test
	void refusesARelayStateWeNeverIssued() {
		assertThatThrownBy(() -> service.complete(samlAuthentication("john.doe@example.com"),
				"authTxn_invented"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
	}

	@Test
	void refusesAMissingRelayState() {
		// Without a transaction there is no institution, and guessing one would mean signing
		// somebody in to an institution nobody selected.
		Authentication authentication = samlAuthentication("john.doe@example.com");

		assertThatThrownBy(() -> service.complete(authentication, null))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
	}

	@Test
	void refusesAReplayedRelayState() {
		AuthTransaction transaction = transactions.open("inst_imperial");
		Authentication authentication = samlAuthentication("john.doe@example.com");
		service.complete(authentication, transaction.id());

		assertThatThrownBy(() -> service.complete(authentication, transaction.id()))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
	}

	@Test
	void refusesAnAuthenticationThatCarriesNoSamlAssertion() {
		Authentication notSaml = new UsernamePasswordAuthenticationToken("john", "secret");
		String relayState = transactions.open("inst_imperial").id();

		assertThatThrownBy(() -> service.complete(notSaml, relayState))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
	}

	@Test
	void refusesAnIdentityWithNoMembershipAtTheSelectedInstitution() {
		Authentication jane = samlAuthentication("jane.roe@example.com");
		String dsu = transactions.open("inst_dsu").id();

		assertThatThrownBy(() -> service.complete(jane, dsu))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void carriesATokenMintedFromTheMappedUser() {
		// The token is issued from the mapped user, after mapping succeeded - so a refused
		// mapping can never produce one, and the token can never disagree with the user beside it.
		SamlLoginResult result = service.complete(samlAuthentication("john.doe@example.com"),
				transactions.open("inst_dsu").id());

		Jwt jwt = decoderAtTheTestsClock().decode(result.token());

		assertThat(jwt.getClaimAsString("userId")).isEqualTo(result.user().userId());
		assertThat(jwt.getClaimAsString("institutionId")).isEqualTo("inst_dsu");
		assertThat(jwt.getExpiresAt()).isEqualTo(result.expiresAt());
	}

	/**
	 * A decoder that judges expiry by the same clock the token was minted with.
	 *
	 * <p>{@code NimbusJwtDecoder} installs Spring's default validator chain, and that chain reads
	 * the <b>system</b> clock. A token this test mints at a fixed instant therefore expires against
	 * wall-clock time, so the test passed for one hour after that instant and failed forever
	 * afterwards - reported as "Jwt expired", which reads like a production bug rather than a
	 * test that pinned itself to a date. Everything here is fixed-clock, so the verifier must be
	 * too.
	 */
	private JwtDecoder decoderAtTheTestsClock() {
		byte[] keyBytes = SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var signingKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey)
						.macAlgorithm(MacAlgorithm.HS256)
						.build();
		decoder.setJwtValidator(new TnfJwtValidator(Clock.fixed(NOW, ZoneOffset.UTC)));
		return decoder;
	}

	private Authentication samlAuthentication(String email) {
		Saml2ResponseAssertionAccessor assertion = new StubAssertion(email,
				Map.of(SamlUserMapper.EMAIL_CLAIM, List.of(email)));
		return new Saml2AssertionAuthentication(assertion, List.of(), "tf-reader");
	}

	/** Stands in for an assertion Spring Security has already validated. */
	private record StubAssertion(String nameId, Map<String, List<Object>> attributes)
			implements Saml2ResponseAssertionAccessor {

		@Override
		public String getNameId() {
			return nameId;
		}

		@Override
		public List<String> getSessionIndexes() {
			return List.of();
		}

		@Override
		public Map<String, List<Object>> getAttributes() {
			return attributes;
		}

		@Override
		public String getResponseValue() {
			return "";
		}
	}
}
