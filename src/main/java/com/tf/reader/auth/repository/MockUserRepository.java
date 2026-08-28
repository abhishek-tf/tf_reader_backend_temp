package com.tf.reader.auth.repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;

/**
 * Maps an authenticated SAML identity, in the context of one institution, onto a TnF user.
 *
 * <p><b>The key is the pair, not the email.</b> One SAML identity may be a member of several
 * institutions and is a different user in each - different userId, different entitled
 * collections. That is what lets a single IdP serve every institution: the assertion says who
 * someone is, and the institution chosen at the start of the transaction says which membership
 * of theirs we are acting on.
 *
 * <p>Seeded around {@code john.doe@example.com}, because that is the NameID samlmock.dev puts
 * in its default assertion, so the demo works without editing the assertion XML. The email is
 * lower-cased before lookup; an IdP is free to vary the case.
 */
@Component
public class MockUserRepository {

	private record Membership(String email, String institutionId) {
	}

	private static final Map<Membership, TnfUser> USERS = Map.of(
			// Real institutions, from the institutions collection - see InstitutionLookup. Same
			// identity, three institutions, three users.
			new Membership("john.doe@example.com", "inst_7f3"),
			new TnfUser("usr_6712ab", UserType.INSTITUTION, "inst_7f3",
					List.of("MEMBER"), List.of("col_medicine")),
			new Membership("john.doe@example.com", "inst_ucl"),
			new TnfUser("usr_8c14de", UserType.INSTITUTION, "inst_ucl",
					List.of("MEMBER"), List.of("col_engineering")),
			new Membership("john.doe@example.com", "inst_leeds"),
			new TnfUser("usr_3f81ab", UserType.INSTITUTION, "inst_leeds",
					List.of("MEMBER"), List.of("col_open")),
			// A second identity at Imperial, so the mapping is visibly by identity and not by
			// institution alone. Change the NameID in the mock IdP's assertion to reach it.
			new Membership("jane.roe@example.com", "inst_7f3"),
			new TnfUser("usr_b920fe", UserType.INSTITUTION, "inst_7f3",
					List.of("MEMBER", "ADMIN"), List.of("col_medicine", "col_engineering")));

	/**
	 * @return the user this identity is, at this institution, or empty if they hold no
	 *         membership there - authenticated is not the same as provisioned
	 */
	public Optional<TnfUser> find(String email, String institutionId) {
		if (email == null || institutionId == null) {
			return Optional.empty();
		}
		// Locale.ROOT, not the JVM default: in a Turkish or Azeri locale "I".toLowerCase() is the
		// dotless "ı", so an address containing a capital I would fail to match the seeded key and
		// a provisioned user would be refused as unprovisioned on some machines and not others.
		// An email address is protocol data and is folded the same way everywhere.
		return Optional.ofNullable(
				USERS.get(new Membership(email.trim().toLowerCase(Locale.ROOT), institutionId)));
	}
}
