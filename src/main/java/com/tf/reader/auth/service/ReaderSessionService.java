package com.tf.reader.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.tf.reader.auth.entity.ReaderSession;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.repository.ReaderSessionRepository;
import com.tf.reader.common.security.JwtProperties;

/**
 * Owns the lifecycle of {@link ReaderSession} and of the opaque refresh token itself: one row per
 * sign in, revoked and replaced on refresh.
 *
 * <p>Mirrors {@code AdminSessionService}. The raw token exists only in the response that hands it
 * out; only a SHA-256 fingerprint is stored, so a leaked database dump yields no usable session.
 */
@Service
public class ReaderSessionService {

	public static final String REASON_ROTATED = "ROTATED";

	/** The contract prefixes a reader session id with {@code rsess_}. */
	static final String SESSION_ID_PREFIX = "rsess_";

	/** 256 bits, so the token is not guessable and needs no key stretching. */
	private static final int REFRESH_TOKEN_BYTES = 32;

	private final ReaderSessionRepository readerSessionRepository;
	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public ReaderSessionService(ReaderSessionRepository readerSessionRepository,
			JwtProperties jwtProperties, Clock clock) {
		this.readerSessionRepository = readerSessionRepository;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
	}

	/**
	 * A refresh token and the row it belongs to. The value is returned to the caller once and is
	 * not recoverable from the row afterwards.
	 */
	public record IssuedRefreshToken(String value, ReaderSession session) {
	}

	/**
	 * Inserts a new session row, snapshotting the identity a fresh access token would need on
	 * refresh.
	 */
	public IssuedRefreshToken createSession(TnfUser user) {
		String tokenValue = newRefreshTokenValue();
		Instant now = this.clock.instant();

		ReaderSession session = new ReaderSession();
		session.setId(newSessionId());
		session.setUserId(user.userId());
		session.setType(user.type());
		session.setInstitutionId(user.institutionId());
		session.setRoles(user.roles());
		session.setCollections(user.collections());
		session.setRefreshTokenHash(fingerprint(tokenValue));
		session.setIssuedAt(now);
		session.setExpiresAt(now.plus(this.jwtProperties.refreshTokenTtl()));

		return new IssuedRefreshToken(tokenValue, this.readerSessionRepository.save(session));
	}

	/**
	 * Claims the row this token belongs to by revoking it, which is what earns the right to issue a
	 * replacement.
	 *
	 * @return the row as it was before revocation, or empty when the token is unknown, already used
	 *         or expired
	 */
	public Optional<ReaderSession> revokeForExchange(String presentedTokenValue) {
		return this.readerSessionRepository.revokeForExchange(fingerprint(presentedTokenValue),
				REASON_ROTATED, this.clock.instant());
	}

	private static String newSessionId() {
		return SESSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
	}

	private String newRefreshTokenValue() {
		byte[] tokenBytes = new byte[REFRESH_TOKEN_BYTES];
		this.secureRandom.nextBytes(tokenBytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
	}

	/** SHA-256, lowercase hex, mirroring {@code AdminSessionService.fingerprint}. */
	static String fingerprint(String tokenValue) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(tokenValue.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required but unavailable", ex);
		}
	}

}
