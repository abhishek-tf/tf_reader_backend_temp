package com.tf.reader.auth.entity;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.tf.reader.auth.model.UserType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per reader sign-in, mirroring {@code AdminSession}.
 *
 * <p>Login inserts a row, refresh revokes it and inserts a new one. The refresh token is opaque,
 * so this row is the only thing that gives it meaning: every refresh is a lookup by
 * {@link #refreshTokenHash}.
 *
 * <p><b>Carries a snapshot of the identity, not just a user id.</b> Unlike {@code AdminSession},
 * which re-reads {@code AdminUserRepository} on every refresh, there is no reader user store keyed
 * by id yet - institutional readers resolve through {@code MockUserRepository}, keyed by
 * {@code (email, institutionId)}. Snapshotting {@code type}/{@code institutionId}/{@code roles}/
 * {@code collections} here at sign-in time is what lets refresh mint a new access token without
 * that lookup. The trade-off: a role or collection change does not reach an already-refreshed
 * session until the reader signs in again.
 */
@Document(collection = "readerSessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReaderSession {

	/** Prefixed {@code rsess_}. */
	@Id
	private String id;

	@Indexed(name = "userId_idx")
	private String userId;

	private UserType type;

	private String institutionId;

	private List<String> roles;

	private List<String> collections;

	/** SHA-256 of the token, lowercase hex. The raw token is never stored. */
	@Indexed(name = "refreshTokenHash_unique", unique = true)
	private String refreshTokenHash;

	private Instant issuedAt;

	/** Mongo removes the document once this passes, bounding growth without a cleanup job. */
	@Indexed(name = "expiresAt_ttl", expireAfter = "0s")
	private Instant expiresAt;

	/** Once set, the row can never be exchanged again. */
	private Instant revokedAt;

	private String revokedReason;

}
