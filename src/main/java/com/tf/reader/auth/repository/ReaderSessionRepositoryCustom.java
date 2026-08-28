package com.tf.reader.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.tf.reader.auth.entity.ReaderSession;

public interface ReaderSessionRepositoryCustom {

	Optional<ReaderSession> revokeForExchange(String refreshTokenHash, String reason, Instant now);

}
