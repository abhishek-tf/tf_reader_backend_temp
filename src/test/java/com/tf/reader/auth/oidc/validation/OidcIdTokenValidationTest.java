package com.tf.reader.auth.oidc.validation;

import com.tf.reader.auth.oidc.client.OidcTransaction;
import com.tf.reader.auth.oidc.client.OidcTransactionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tf.reader.auth.oidc.mock.security.MockOidcKeyService;
import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.MockOidcTestProfile;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * What actually happens to an ID token: signature, issuer, audience, expiry, nonce.
 *
 * <p><b>Every token here is signed for real</b>, and the good ones are signed by the running mock
 * provider's own key - fetched by the application's own {@code OidcIdTokenDecoder} from the
 * running JWKS endpoint. So this is not a test of a reimplementation of the rules; it is the
 * production path, exercised.
 *
 * <p>The negative cases are the point. A second RSA key pair is generated here, and tokens signed
 * with it are structurally perfect: right issuer, right audience, right nonce, not expired, valid
 * RS256 signature. The only thing wrong with them is <em>whose</em> signature it is - which is
 * exactly the attack a JWKS check exists to stop, and exactly the bug a mock without real
 * cryptography could never catch.
 */
// DEFINED_PORT, because two hops here are real HTTP: the decoder fetches the provider's JWKS to
// verify a signature. Under MockMvc there is nothing listening and every positive case would fail
// for a reason that has nothing to do with token validation.
//
// The properties here must match OidcEndToEndAuthFlowTest's exactly: both extend
// MockOidcTestProfile and so share its static PORT, and Spring only reuses one real server for
// both when their context configuration - these properties included - is identical. Diverge and
// both try to bind that same port in the same JVM.
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = { "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET,
				"spring.profiles.active=" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcIdTokenValidationTest extends MockOidcTestProfile {

	/** A key the provider does not publish. Perfectly valid; simply not theirs. */
	private static KeyPair impostorKey;

	@Autowired
	private OidcIdTokenValidator validator;

	@Autowired
	private OidcTransactionStore transactions;

	/**
	 * The running provider's key service.
	 *
	 * <p>Injected so these tokens are signed by the <em>same</em> key the JWKS endpoint publishes -
	 * a second key would prove nothing about the decoder. It signs on request and never hands the
	 * private key out, so there is no accessor here to misuse.
	 */
	@Autowired
	private MockOidcKeyService providerKeys;

	@BeforeAll
	static void generateImpostorKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		impostorKey = generator.generateKeyPair();
	}

	// ───────────────────────────── the happy path ─────────────────────────────

	@Test
	void aTokenSignedByTheProviderIsAccepted() {
		OidcTransaction transaction = transactions.open("inst_ucl");

		Jwt verified = validator.validate(providerSigned(claims(transaction.nonce())), transaction);

		assertThat(verified.getClaimAsString("email")).isEqualTo("john.doe@example.com");
		assertThat(verified.getClaimAsString("sub")).isEqualTo("mock-user-001");
		assertThat(verified.getIssuer()).hasToString(ISSUER);
	}

	// ───────────────────────────── signature ─────────────────────────────

	@Test
	void aTokenSignedByAnybodyElseIsRefused() {
		// The check that matters most. Without it an ID token is a base64 string anybody can write,
		// naming any user in the directory. Note how little is wrong with this token: only the key.
		OidcTransaction transaction = transactions.open("inst_ucl");

		assertRefused(signedWith(impostorKey, claims(transaction.nonce())), transaction);
	}

	@Test
	void aTokenWithATamperedPayloadIsRefused() {
		// Re-encoding the payload of a legitimately signed token breaks the signature, so the email
		// a user is looked up by cannot be swapped for somebody else's.
		OidcTransaction transaction = transactions.open("inst_ucl");
		String[] parts = providerSigned(claims(transaction.nonce())).split("\\.");
		String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
				java.nio.charset.StandardCharsets.UTF_8);

		String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.replace("john.doe", "jane.roe")
						.getBytes(java.nio.charset.StandardCharsets.UTF_8))
				+ "." + parts[2];

		assertRefused(tampered, transaction);
	}

	@Test
	void anUnsignedTokenIsRefused() {
		// alg=none. The decoder is pinned to RS256, so a token claiming no algorithm is not
		// "trivially valid" - it is unreadable.
		OidcTransaction transaction = transactions.open("inst_ucl");
		String unsigned = base64("{\"alg\":\"none\"}") + "."
				+ base64("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + CLIENT_ID + "\"}") + ".";

		assertRefused(unsigned, transaction);
	}

	@Test
	void aTokenThatIsNotAJwtAtAllIsRefused() {
		OidcTransaction transaction = transactions.open("inst_ucl");

		for (String rubbish : new String[] { "not-a-jwt", "..", "a.b.c", "" }) {
			assertRefused(rubbish, transaction);
		}
	}

	@Test
	void aMissingIdTokenIsRefusedRatherThanIgnored() {
		// A token response with no id_token means a provider configured without the openid scope.
		// Signing somebody in on the access token instead would be signing them in on an
		// authorization grant that asserts nothing about who they are.
		OidcTransaction transaction = transactions.open("inst_ucl");

		assertRefused(null, transaction);
	}

	// ───────────────────────────── issuer ─────────────────────────────

	@Test
	void aTokenFromAnotherIssuerIsRefused() {
		// Signed by the real provider key, but claiming to come from somebody else. This is the
		// check Spring's own OidcIdTokenValidator SKIPS when a registration has no issuerUri -
		// which is always, for Azure AD B2C - and the reason OidcIdTokenDecoder adds it explicitly.
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = claims(transaction.nonce())
				.issuer("https://attacker.example.com/v2.0/");

		assertRefused(providerSigned(claims), transaction);
	}

	@Test
	void aTokenWithNoIssuerIsRefused() {
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.subject("mock-user-001")
				.audience(CLIENT_ID)
				.claim("email", "john.doe@example.com")
				.claim("nonce", transaction.nonce())
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))));

		assertRefused(providerSigned(claims), transaction);
	}

	// ───────────────────────────── audience ─────────────────────────────

	@Test
	void aTokenIssuedForAnotherApplicationIsRefused() {
		// aud is our client id. A token the provider minted for a different application is signed
		// by the same key and would otherwise verify perfectly.
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = claims(transaction.nonce()).audience("some-other-application");

		assertRefused(providerSigned(claims), transaction);
	}

	@Test
	void aTokenWithNoAudienceIsRefused() {
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = claims(transaction.nonce()).audience(java.util.List.of());

		assertRefused(providerSigned(claims), transaction);
	}

	@Test
	void aTokenNamingUsAmongSeveralAudiencesIsAccepted() {
		// aud MAY be an array, and ours being in it is what the specification asks. Pinned so the
		// check stays "contains" rather than drifting to "equals" and breaking a conforming
		// provider.
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = claims(transaction.nonce())
				.audience(java.util.List.of("another-app", CLIENT_ID));

		assertThat(validator.validate(providerSigned(claims), transaction)).isNotNull();
	}

	// ───────────────────────────── expiry ─────────────────────────────

	@Test
	void anExpiredTokenIsRefused() {
		OidcTransaction transaction = transactions.open("inst_ucl");
		// Five minutes past, comfortably beyond the 60 seconds of clock skew Nimbus allows by
		// default - unlike our own tokens, where we are the only issuer and verifier and
		// TnfJwtValidator allows none, here there genuinely are two clocks.
		JWTClaimsSet.Builder claims = claims(transaction.nonce())
				.issueTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
				.expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(5))));

		assertRefused(providerSigned(claims), transaction);
	}

	// ───────────────────────────── nonce ─────────────────────────────

	@Test
	void aTokenMintedForAnotherSignInIsRefused() {
		// The nonce check, and the reason it exists even though state already matched. This token
		// is real: right key, right issuer, right audience, not expired. It simply belongs to a
		// different authorization request - which is precisely a replay.
		OidcTransaction ours = transactions.open("inst_ucl");
		OidcTransaction somebodyElses = transactions.open("inst_7f3");

		assertRefused(providerSigned(claims(somebodyElses.nonce())), ours);
	}

	@Test
	void aTokenWithNoNonceIsRefused() {
		// Absent must be a failure, not a pass. "If the value is present, compare it" is the shape
		// of this check that does nothing: a token minted without a nonce sails straight through it.
		OidcTransaction transaction = transactions.open("inst_ucl");
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("mock-user-001")
				.audience(CLIENT_ID)
				.claim("email", "john.doe@example.com")
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))));

		assertRefused(providerSigned(claims), transaction);
	}

	@Test
	void anEmptyNonceDoesNotMatchAnything() {
		OidcTransaction transaction = transactions.open("inst_ucl");

		assertRefused(providerSigned(claims(transaction.nonce()).claim("nonce", "")), transaction);
	}

	// ───────────────────────────── helpers ─────────────────────────────

	private void assertRefused(String idToken, OidcTransaction transaction) {
		assertThatThrownBy(() -> validator.validate(idToken, transaction))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.OIDC_AUTHENTICATION_FAILED);
	}

	/** The claims a good ID token carries, for this sign-in. */
	private static JWTClaimsSet.Builder claims(String nonce) {
		return new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("mock-user-001")
				.audience(CLIENT_ID)
				.claim("email", "john.doe@example.com")
				.claim("name", "John Doe")
				.claim("nonce", nonce)
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))));
	}

	/** Signed by the running mock provider's real key, with its real kid. */
	private String providerSigned(JWTClaimsSet.Builder claims) {
		return providerKeys.sign(claims.build());
	}

	/** Signed by a key the provider does not publish - a perfectly valid RSA signature, just not theirs. */
	private static String signedWith(KeyPair key, JWTClaimsSet.Builder claims) {
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(UUID.randomUUID().toString()).build(),
					claims.build());
			jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
			return jwt.serialize();
		}
		catch (Exception failure) {
			throw new IllegalStateException("could not sign the impostor token", failure);
		}
	}

	private static String base64(String json) {
		return java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

}
