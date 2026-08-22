package com.tf.reader.auth.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.tf.reader.auth.model.CurrentUser;

/**
 * Turns a verified JWT into the request's authenticated identity.
 *
 * <p>This is the only place claims are read. Every field of {@link CurrentUser} comes from the
 * token and nowhere else - not from a query parameter, a header, a path variable or a body.
 * That is the whole point: after sign-in the token is the single source of truth about who the
 * caller is, and in particular about which institution they belong to.
 *
 * <p>Runs after signature verification and {@link TnfJwtValidator}, so the claims it reads are
 * known to be present and well formed.
 */
@Component
public class CurrentUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	/**
	 * Spring's convention, and not decoration: {@code hasRole("MEMBER")} in the authorization
	 * step looks for an authority named {@code ROLE_MEMBER}. Choosing anything else here means
	 * every later rule silently matches nothing.
	 */
	static final String ROLE_PREFIX = "ROLE_";

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		// getClaimAsStringList returns null when the claim is absent. TnfJwtValidator rejects
		// such tokens before they reach here, but test slices and mock authentications can
		// bypass the validator — null is guarded explicitly so CurrentUser's List.copyOf()
		// does not NullPointerException in those paths.
		List<String> roles = jwt.getClaimAsStringList("roles");
		if (roles == null) {
			roles = List.of();
		}
		List<String> collections = jwt.getClaimAsStringList("collections");
		if (collections == null) {
			collections = List.of();
		}

		CurrentUser currentUser = new CurrentUser(
				jwt.getClaimAsString("userId"),
				TnfJwtValidator.parseType(jwt.getClaimAsString("type")),
				jwt.getClaimAsString("institutionId"),
				roles,
				collections);

		return new CurrentUserAuthenticationToken(currentUser, jwt, authorities(roles));
	}

	private List<GrantedAuthority> authorities(List<String> roles) {
		return roles.stream()
				.map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
				.toList();
	}
}
