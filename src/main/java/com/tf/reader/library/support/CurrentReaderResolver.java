package com.tf.reader.library.support;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Turns the request's authenticated identity into this module's {@link ReaderIdentity}.
 *
 * <p><b>It does not read claims.</b> The auth module's {@code CurrentUserJwtConverter} is the only
 * place in the backend that does, and it runs after signature verification and validation — so by
 * the time a request reaches a library endpoint the identity is already decided. Re-reading the
 * token here would be a second interpretation of the same claims, free to disagree with the first.
 *
 * <p><b>The one place this module imports another lane's internals, and deliberately.</b>
 * {@code auth.api.SessionQuery} is the published seam and the right long-term answer, but it has no
 * implementation — injecting it would fail context startup rather than merely returning nothing. We
 * reach into {@code auth.model} for that reason and no other.
 */
@Component
public class CurrentReaderResolver {

	// TODO: swap to auth.api.SessionQuery when it has an implementation.

	/**
	 * @throws ApiException 401 if the request carries no verified identity. Deny by default: a
	 *                      library endpoint that falls back to any other source of a userId is one
	 *                      that can be asked for somebody else's shelf
	 */
	public ReaderIdentity require(Authentication authentication) {
		if (authentication != null && authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof CurrentUser reader
				&& reader.userId() != null && !reader.userId().isBlank()) {
			return new ReaderIdentity(reader.userId(), reader.institutionId());
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to see your library.");
	}

}
