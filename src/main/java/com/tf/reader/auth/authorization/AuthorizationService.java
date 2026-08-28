package com.tf.reader.auth.authorization;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Role;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The two authorization decisions every capability shares: does this caller hold a role, and
 * does this resource belong to their institution.
 *
 * <p><b>Called from a service, never only from a controller.</b> A controller check is bypassed
 * the moment a second entry point - another service, a scheduled job, a test - calls the same
 * method. Putting the decision next to the operation it guards means there is no path to the
 * operation that skips it.
 *
 * <p><b>It takes {@link CurrentUser} as a parameter and never reads the security context.</b>
 * Ports and services are also called from scheduled threads, where there is no request and no
 * context at all; a service that reached for ambient state would work under HTTP and silently
 * authorize nothing, or throw, under a scheduler.
 *
 * <p>Every field it reads comes from a token whose signature was verified before the request
 * reached any controller. There is no overload accepting a role or an institution from a caller.
 *
 * <p><b>Not entitlement.</b> "May this user perform this operation" is here. "Does this
 * institution's plan cover this title" is the entitlement owner's, reached through
 * {@code EntitlementPort}, and must not be duplicated in this class. A service will typically
 * call both: authorization first, because it is local and cheap, then entitlement.
 */
@Service
public class AuthorizationService {

	/**
	 * Requires that the caller holds a role.
	 *
	 * @throws ApiException 403 {@code FORBIDDEN_ROLE} if they do not
	 */
	public void requireRole(CurrentUser currentUser, Role required) {
		requireAnyRole(currentUser, required);
	}

	/**
	 * Requires that the caller holds at least one of the given roles.
	 *
	 * @throws ApiException 403 {@code FORBIDDEN_ROLE} if they hold none of them
	 */
	public void requireAnyRole(CurrentUser currentUser, Role... permitted) {
		if (!hasAnyRole(currentUser, permitted)) {
			// The message names what was needed, not what the caller has. Echoing somebody's
			// roles back at them tells an attacker how close they got.
			throw new ApiException(ErrorCode.FORBIDDEN_ROLE,
					"This operation requires one of: " + Arrays.toString(permitted) + ".");
		}
	}

	/** Non-throwing form, for a caller that needs to branch rather than refuse. */
	public boolean hasAnyRole(CurrentUser currentUser, Role... permitted) {
		if (currentUser == null || permitted.length == 0) {
			return false;
		}
		List<String> held = currentUser.roles();
		return Arrays.stream(permitted).anyMatch(role -> held.contains(role.name()));
	}

	/**
	 * Requires that an institution-scoped resource belongs to the caller's institution.
	 *
	 * <p>The caller's institution comes from {@link CurrentUser}, which came from the token.
	 * Nothing a client sends can reach this comparison.
	 *
	 * @param resourceInstitutionId the institution the resource being touched belongs to
	 * @throws ApiException 403 {@code WRONG_INSTITUTION} if it belongs to another institution,
	 *                      if the caller belongs to none, or if the resource is not
	 *                      institution-scoped
	 */
	public void requireSameInstitution(CurrentUser currentUser, String resourceInstitutionId) {
		if (currentUser == null || !currentUser.belongsToAnInstitution()) {
			// An individual subscriber has no institution. That is emphatically not "every
			// institution" - it is none, so no institution-scoped resource is theirs.
			throw new ApiException(ErrorCode.WRONG_INSTITUTION,
					"This resource belongs to an institution, and you are not a member of one.");
		}
		if (!StringUtils.hasText(resourceInstitutionId)) {
			// Refused rather than allowed, and this is the trap the method exists to avoid: an
			// individual has a null institution, so comparing two nulls for equality would let
			// anyone reach anything unscoped. If a resource is not institution-scoped, it does
			// not belong to this check at all.
			throw new ApiException(ErrorCode.WRONG_INSTITUTION,
					"This resource carries no institution to check against.");
		}
		if (!currentUser.institutionId().equals(resourceInstitutionId)) {
			// Does not name the resource's institution: whether inst_ucl holds a given item is
			// not something a member of another institution should learn from an error message.
			throw new ApiException(ErrorCode.WRONG_INSTITUTION,
					"This resource belongs to another institution.");
		}
	}
}
