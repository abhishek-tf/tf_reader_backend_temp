package com.tf.reader.auth.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Role;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The two authorization decisions, unit tested. No HTTP: these run from a service layer, and
 * one is also reachable from a scheduled thread where there is no request at all.
 */
class AuthorizationServiceTest {

	private static final CurrentUser MEMBER = new CurrentUser("usr_6712ab", UserType.INSTITUTION,
			"inst_7f3", List.of("MEMBER"), List.of("col_medicine"));

	private static final CurrentUser ADMIN = new CurrentUser("usr_b920fe", UserType.INSTITUTION,
			"inst_7f3", List.of("MEMBER", "ADMIN"), List.of("col_medicine"));

	private static final CurrentUser SUBSCRIBER = new CurrentUser("usr_9f01cd", UserType.INDIVIDUAL,
			null, List.of("SUBSCRIBER"), List.of("col_open"));

	private final AuthorizationService authorization = new AuthorizationService();

	@Nested
	class Roles {

		@Test
		void aMemberPassesAMemberCheck() {
			assertThatCode(() -> authorization.requireRole(MEMBER, Role.MEMBER))
					.doesNotThrowAnyException();
		}

		@Test
		void anAdminPassesAnAdminCheck() {
			assertThatCode(() -> authorization.requireRole(ADMIN, Role.ADMIN))
					.doesNotThrowAnyException();
		}

		@Test
		void aMemberIsRefusedAnAdminCheck() {
			assertThatThrownBy(() -> authorization.requireRole(MEMBER, Role.ADMIN))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isEqualTo(ErrorCode.FORBIDDEN_ROLE);
		}

		@Test
		void aSubscriberIsRefusedAnAdminCheck() {
			assertThatThrownBy(() -> authorization.requireRole(SUBSCRIBER, Role.ADMIN))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isEqualTo(ErrorCode.FORBIDDEN_ROLE);
		}

		@Test
		void aRoleFailureIsNeverReportedAsAnInstitutionFailure() {
			// The contract overloads WRONG_INSTITUTION for this today. The app has to be able to
			// tell "you are in the wrong tenant" from "you lack a role" - they read differently
			// to a user and are fixed differently.
			assertThatThrownBy(() -> authorization.requireRole(MEMBER, Role.ADMIN))
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isNotEqualTo(ErrorCode.WRONG_INSTITUTION);
		}

		@Test
		void holdingAnyOneOfSeveralRolesIsEnough() {
			assertThatCode(() -> authorization.requireAnyRole(SUBSCRIBER, Role.ADMIN, Role.SUBSCRIBER))
					.doesNotThrowAnyException();
			assertThatThrownBy(() -> authorization.requireAnyRole(MEMBER, Role.ADMIN, Role.SUBSCRIBER))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void requiringNoRoleAtAllRefusesRatherThanPermits() {
			// A call site that passes an empty list has a bug. Interpreting it as "no role
			// needed" would turn that bug into an open door.
			assertThat(authorization.hasAnyRole(ADMIN)).isFalse();
			assertThatThrownBy(() -> authorization.requireAnyRole(ADMIN))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void anAbsentCallerIsRefused() {
			// If a service is ever reached with no authenticated user, that must be a refusal and
			// not a pass. It should be impossible; this makes it harmless if it happens.
			assertThat(authorization.hasAnyRole(null, Role.MEMBER)).isFalse();
			assertThatThrownBy(() -> authorization.requireRole(null, Role.MEMBER))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void theRefusalMessageDoesNotEchoTheCallersRoles() {
			assertThatThrownBy(() -> authorization.requireRole(MEMBER, Role.ADMIN))
					.hasMessageContaining("ADMIN")
					.hasMessageNotContaining("MEMBER");
		}
	}

	@Nested
	class Institutions {

		@Test
		void aMemberReachesTheirOwnInstitutionsResource() {
			assertThatCode(() -> authorization.requireSameInstitution(MEMBER, "inst_7f3"))
					.doesNotThrowAnyException();
		}

		@Test
		void aMemberIsRefusedAnotherInstitutionsResource() {
			assertThatThrownBy(() -> authorization.requireSameInstitution(MEMBER, "inst_ucl"))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isEqualTo(ErrorCode.WRONG_INSTITUTION);
		}

		@Test
		void anIndividualBelongsToNoInstitutionRatherThanAllOfThem() {
			// The failure that would matter most: reading "no institution" as "unscoped, so
			// everything" would hand every institution's data to every individual subscriber.
			for (String institution : List.of("inst_7f3", "inst_ucl", "inst_leeds")) {
				assertThatThrownBy(() -> authorization.requireSameInstitution(SUBSCRIBER, institution))
						.describedAs("an individual must not reach %s", institution)
						.isInstanceOf(ApiException.class)
						.extracting(thrown -> ((ApiException) thrown).getCode())
						.isEqualTo(ErrorCode.WRONG_INSTITUTION);
			}
		}

		@Test
		void twoAbsentInstitutionsDoNotMatchEachOther() {
			// The trap this method exists to close. An individual's institutionId is null, so a
			// plain equality check would make null == null an ALLOW and let any individual reach
			// anything that happens to arrive without an institution on it.
			assertThatThrownBy(() -> authorization.requireSameInstitution(SUBSCRIBER, null))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isEqualTo(ErrorCode.WRONG_INSTITUTION);
		}

		@Test
		void aResourceWithNoInstitutionIsNotWavedThrough() {
			// Not institution-scoped means it does not belong to this check. Refusing makes the
			// mistake visible; allowing would make it invisible.
			assertThatThrownBy(() -> authorization.requireSameInstitution(MEMBER, null))
					.isInstanceOf(ApiException.class);
			assertThatThrownBy(() -> authorization.requireSameInstitution(MEMBER, "   "))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void theRefusalDoesNotNameTheOtherInstitution() {
			// Whether inst_ucl holds a given resource is not something a member of another
			// institution should learn from an error body.
			assertThatThrownBy(() -> authorization.requireSameInstitution(MEMBER, "inst_ucl"))
					.hasMessageNotContaining("inst_ucl");
		}

		@Test
		void theInstitutionComesFromTheUserAndTheresNoWayToPassOneIn() {
			// The signature is the guarantee: the caller's institution is read from CurrentUser,
			// and the only other argument is the resource's. There is no overload that accepts a
			// caller institution, so no request value can become one.
			assertThat(AuthorizationService.class.getMethods())
					.filteredOn(method -> method.getName().equals("requireSameInstitution"))
					.singleElement()
					.satisfies(method -> assertThat(method.getParameterTypes())
							.containsExactly(CurrentUser.class, String.class));
		}
	}
}
