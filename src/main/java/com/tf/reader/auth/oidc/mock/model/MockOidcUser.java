package com.tf.reader.auth.oidc.mock.model;

/**
 * The single user the local mock provider knows how to authenticate.
 *
 * <p>Pre-populated, because the mock stands in for a directory and a password prompt, and
 * neither is what we are testing: the flow is. Configurable, because signing in as a second
 * identity - to see {@code USER_NOT_PROVISIONED}, or to reach the ADMIN fixture - should be a
 * property and not a code change.
 *
 * <p><b>The default email is {@code john.doe@example.com} deliberately.</b> It is the address
 * {@code MockUserRepository} is seeded around and the one samlmock.dev asserts by default, so
 * the same person signs in over both protocols and the two legs can be seen converging on one
 * {@code userId} without editing any fixture.
 *
 * @param sub   the provider's stable identifier for this user. Ends up as the {@code sub} claim
 *              and, through the mapper, as the recorded {@code oidcSubject}
 * @param email the address the ID token asserts, and the key our user lookup uses
 * @param name  a display name, carried because real providers carry one. Read by nothing:
 *              {@code TnfUser} has no name field and inventing one is not this task
 */
public record MockOidcUser(String sub, String email, String name) {

	public MockOidcUser {
		sub = (sub != null) ? sub : "mock-user-001";
		email = (email != null) ? email : "john.doe@example.com";
		name = (name != null) ? name : "John Doe";
	}

	public static MockOidcUser defaultUser() {
		return new MockOidcUser(null, null, null);
	}
}
