package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;

import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.repository.MockUserRepository;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The mapper is where "one IdP, many institutions" is actually decided, so these tests are
 * mostly about the same SAML identity resolving differently per institution.
 *
 * <p>Every assertion here is a stub: the mapper runs <em>after</em> Spring Security has
 * validated the real thing, so these test the mapping and nothing about trust. Nothing here
 * touches samlmock.dev.
 */
class SamlUserMapperTest {

	private final SamlUserMapper mapper = new SamlUserMapper(new MockUserRepository());

	@Test
	void mapsAnAssertionToTheTnfUserForThatInstitution() {
		TnfUser user = mapper.map(assertion("john.doe@example.com"), "inst_7f3");

		assertThat(user.userId()).isEqualTo("usr_6712ab");
		assertThat(user.type()).isEqualTo(UserType.INSTITUTION);
		assertThat(user.institutionId()).isEqualTo("inst_7f3");
		assertThat(user.roles()).containsExactly("MEMBER");
		assertThat(user.collections()).containsExactly("col_medicine");
	}

	@Test
	void oneSamlIdentityResolvesToADifferentUserAtEachInstitution() {
		// The whole point of the architecture: the assertion is byte-identical, and only the
		// institution recovered from our own transaction differs.
		Saml2ResponseAssertionAccessor sameAssertion = assertion("john.doe@example.com");

		TnfUser imperial = mapper.map(sameAssertion, "inst_7f3");
		TnfUser dsu = mapper.map(sameAssertion, "inst_ucl");
		TnfUser xyz = mapper.map(sameAssertion, "inst_leeds");

		assertThat(List.of(imperial.userId(), dsu.userId(), xyz.userId()))
				.containsExactly("usr_6712ab", "usr_8c14de", "usr_3f81ab")
				.doesNotHaveDuplicates();
		assertThat(List.of(imperial.institutionId(), dsu.institutionId(), xyz.institutionId()))
				.containsExactly("inst_7f3", "inst_ucl", "inst_leeds");
		assertThat(imperial.collections()).isNotEqualTo(dsu.collections());
	}

	@Test
	void differentIdentitiesAtOneInstitutionResolveToDifferentUsers() {
		TnfUser john = mapper.map(assertion("john.doe@example.com"), "inst_7f3");
		TnfUser jane = mapper.map(assertion("jane.roe@example.com"), "inst_7f3");

		assertThat(john.userId()).isNotEqualTo(jane.userId());
		assertThat(jane.roles()).contains("ADMIN");
	}

	@Test
	void refusesAnIdentityWithNoMembershipAtThatInstitution() {
		// Jane exists, but only at Imperial. Authenticated is not the same as provisioned.
		assertThatThrownBy(() -> mapper.map(assertion("jane.roe@example.com"), "inst_ucl"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void refusesAnIdentityUnknownToUsEntirely() {
		// A valid SAML login by somebody we have never provisioned is still a refusal. The IdP
		// vouching for an identity does not make it one of our users.
		assertThatThrownBy(() -> mapper.map(assertion("stranger@example.com"), "inst_7f3"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void refusesAnUnknownInstitution() {
		assertThatThrownBy(() -> mapper.map(assertion("john.doe@example.com"), "inst_nowhere"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
	}

	@Test
	void refusesAnAssertionWithNoIdentityAttributeAtAll() {
		assertThatThrownBy(() -> mapper.map(new StubAssertion("", Map.of()), "inst_7f3"))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getCode())
				.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
	}

	@Test
	void fallsBackToTheNameIdWhenNoEmailAttributeIsAsserted() {
		// Justified rather than arbitrary: the NameID is the SAML subject, and samlmock.dev puts
		// the same value in both places.
		Saml2ResponseAssertionAccessor nameIdOnly =
				new StubAssertion("john.doe@example.com", Map.of());

		assertThat(mapper.map(nameIdOnly, "inst_7f3").userId()).isEqualTo("usr_6712ab");
	}

	@Test
	void emailCaseDoesNotChangeTheUser() {
		assertThat(mapper.map(assertion("John.Doe@Example.com"), "inst_7f3").userId())
				.isEqualTo("usr_6712ab");
	}

	@Test
	void ignoresAnyIdentityTheAssertionTriesToClaimForItself() {
		// An assertion carrying userId/institutionId attributes must not be able to choose who it
		// is or which institution it belongs to. Identity comes from the email claim; the
		// institution comes from the caller, which is the server-side transaction.
		Saml2ResponseAssertionAccessor overreaching = new StubAssertion("john.doe@example.com",
				Map.of(SamlUserMapper.EMAIL_CLAIM, List.of("john.doe@example.com"),
						"userId", List.of("usr_admin"),
						"institutionId", List.of("inst_ucl"),
						"roles", List.of("ADMIN")));

		TnfUser user = mapper.map(overreaching, "inst_7f3");

		assertThat(user.userId()).isEqualTo("usr_6712ab");
		assertThat(user.institutionId()).isEqualTo("inst_7f3");
		assertThat(user.roles()).containsExactly("MEMBER");
	}

	@Test
	void producesNoTokenOfAnyKind() {
		// TnfUser is an identity, not a credential. If a token, secret or assertion field ever
		// appears on it, this stage has absorbed work that belongs to TokenService.
		assertThat(TnfUser.class.getRecordComponents())
				.extracting(RecordComponent::getName)
				.containsExactly("userId", "type", "institutionId", "roles", "collections");
	}

	private Saml2ResponseAssertionAccessor assertion(String email) {
		return new StubAssertion(email, Map.of(SamlUserMapper.EMAIL_CLAIM, List.of(email)));
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
