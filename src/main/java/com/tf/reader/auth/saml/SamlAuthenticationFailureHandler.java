package com.tf.reader.auth.saml;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.error.TraceIds;

/**
 * Turns a rejected SAML response into a redirect back to the app.
 *
 * <p>Spring Security's own default redirects to an error page; a JSON body, which is what this
 * class wrote before the deep-link callback existed, is just as useless here - the browser is
 * mid-redirect from the IdP, not a fetch call that could read one. Either way the only thing that
 * can act on the refusal is the app itself, at {@link SamlAuthenticationSuccessHandler#DEEP_LINK_CALLBACK}.
 *
 * <p>The reason is logged but never returned. A caller learns that sign-in failed, not which
 * check failed - "signature did not verify" and "audience did not match" are useful to an
 * attacker probing our configuration and to nobody else.
 */
@Component
public class SamlAuthenticationFailureHandler implements AuthenticationFailureHandler {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(SamlAuthenticationFailureHandler.class);

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String traceId = TraceIds.newTraceId();
		log.warn("SAML authentication rejected [traceId={}]: {}", traceId, exception.getMessage());

		response.sendRedirect(SamlAuthenticationSuccessHandler.DEEP_LINK_CALLBACK
				+ "?error=" + ErrorCode.SAML_AUTHENTICATION_FAILED.name());
	}
}
