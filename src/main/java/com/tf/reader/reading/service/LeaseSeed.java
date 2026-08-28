package com.tf.reader.reading.service;

import java.time.Instant;

// One token the reconciler found live in the DB, waiting to be written back into Redis.
record LeaseSeed(String token, Instant expiresAt) {
}
