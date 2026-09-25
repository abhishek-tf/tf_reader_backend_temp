package com.tf.reader.reading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.tf.reader.content.api.Format;
import com.tf.reader.content.api.Intent;

/**
 * Request body for {@code POST /api/v1/reading-sessions}, for both reading and downloading.
 *
 * <p><b>There is no userId and no institutionId.</b> Identity arrives as the authenticated
 * principal, which exists only because a token passed signature and claim validation before the
 * controller was reached. Adding an identity field here would be adding the one thing a caller
 * must not be able to assert about itself.
 *
 * <p>{@code devicePublicKey} is validated for presence here and for decodability in the service.
 * Those are different failures and the app does different things with them: absent is a client bug,
 * undecodable is a key-generation bug on the device.
 */
public record ReadingSessionRequest(

		@NotBlank(message = "is required")
		String itemId,

		@NotNull(message = "is required")
		Format format,

		@NotNull(message = "is required")
		Intent intent,

		/**
		 * Base64 of the RAW public key bytes — not a PEM, not a JSON web key. The catalogue team
		 * rewrap the book key for this device with it, and the fingerprint of it is what the device
		 * cap counts, so the same device presenting the same key is one device with nothing enrolled.
		 */
		@NotBlank(message = "is required")
		String devicePublicKey,

		/** Ask for the encrypted search index too. Absent from the response if the book has none. */
		boolean wantSearchIndex,

		/**
		 * 1-based chapter number. Null/absent defaults to 1, so a single-file book is
		 * unaffected. Required when the client wants a specific chapter of a chaptered
		 * BOOK or ARTICLE.
		 */
		Integer partNumber) {
}
