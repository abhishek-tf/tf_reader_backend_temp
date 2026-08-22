package com.tf.reader.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	TOO_MANY_IDS(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	// No bearer token was presented at all on a route that requires one.
	TOKEN_MISSING(HttpStatus.UNAUTHORIZED),
	// A token was presented and is not usable: bad signature, malformed, or missing a claim we
	// require. Deliberately one code for all three - which part of a rejected token failed is
	// useful to somebody probing us and to nobody else.
	TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
	// The SAML response did not validate, or the sign-in transaction it referred to was unknown,
	// already used or expired. Deliberately one code for all of those.
	SAML_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED),

	/**
	 * 401. The OIDC sign-in did not complete: the authorization code could not be exchanged, the
	 * ID token did not validate, the {@code state} did not match the request we sent, or the
	 * sign-in transaction it referred to was unknown, already used or expired.
	 *
	 * <p>The OIDC counterpart of {@link #SAML_AUTHENTICATION_FAILED}, and one code for all of
	 * those for the same reason: which part of a failed sign-in failed is useful to somebody
	 * probing our configuration and to nobody else. No upstream provider error code or
	 * description is ever copied into the response.
	 */
	OIDC_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED),
	// The SAML assertion was valid, but that identity holds no membership at the institution the
	// sign-in was started for. Authenticated is not the same as provisioned.
	USER_NOT_PROVISIONED(HttpStatus.FORBIDDEN),
	// The resource belongs to another institution. Distinct from FORBIDDEN_ROLE: one is the wrong
	// tenant, the other is the right tenant without the role, and those need different messages.
	WRONG_INSTITUTION(HttpStatus.FORBIDDEN),
	// The caller is authenticated but does not hold a role this operation requires.
	FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
	FORBIDDEN_SCOPE(HttpStatus.FORBIDDEN),
	FORBIDDEN_INSTITUTION_MISMATCH(HttpStatus.FORBIDDEN),
	NO_ENTITLEMENT(HttpStatus.FORBIDDEN),
	DOWNLOAD_NOT_PERMITTED(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	CODE_TAKEN(HttpStatus.CONFLICT),
	CONTENT_NOT_READY(HttpStatus.CONFLICT),
	STALE_VERSION(HttpStatus.CONFLICT),
	// Added by Deepak (reading) — the read broker refuses a full elite title with this
	// code. Raise with Haripriya (common/error owner) rather than treating as settled.
	NO_COPIES_AVAILABLE(HttpStatus.CONFLICT),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

	// Added by Haripriyaa (common/error owner), from the API Reference. Every status below is
	// fixed by one of its response examples, apart from the one noted as inferred.
	INVALID_DEVICE_PUBLIC_KEY(HttpStatus.BAD_REQUEST),
	// The one token code that survives "never say which part of a token failed", because the
	// app has to know to clear its keychain rather than retry.
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
	ENTITLEMENT_EXPIRED(HttpStatus.FORBIDDEN),
	// 403 inferred — the only one of these with no response example in the API Reference. Pin
	// it at the Contracts Gate rather than assuming it follows the rest of the DenyReason family.
	ENTITLEMENT_SUSPENDED(HttpStatus.FORBIDDEN),
	INSTITUTION_INACTIVE(HttpStatus.FORBIDDEN),
	// 403, not 409. It reads like a conflict and is not one; the API Reference example is
	// explicit, and a 409 here would have every client branching on the wrong status.
	DEVICE_LIMIT_REACHED(HttpStatus.FORBIDDEN),
	NO_ACTIVE_LOAN(HttpStatus.CONFLICT),
	// The repeat-return code, and the whole replay guard for the offline return outbox: the
	// client treats this 409 as success, so renaming it silently breaks that retry path.
	LOAN_NOT_ACTIVE(HttpStatus.CONFLICT),
	OFFER_EXPIRED(HttpStatus.CONFLICT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public HttpStatus status() {
		return status;
	}
}
