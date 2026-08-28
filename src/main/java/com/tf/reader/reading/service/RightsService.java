package com.tf.reader.reading.service;

import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.content.api.Format;
import com.tf.reader.content.api.Intent;

/**
 * Enforces reading vs. downloading rights per tier.
 *
 * <p>Rule: Download of a concurrent (ELITE) title is refused server-side with
 * {@code ErrorCode.DOWNLOAD_NOT_PERMITTED} (HTTP 403 Forbidden). This is tier-only — format plays
 * no part in it. Audio follows exactly the same rule as PDF/EPUB: downloadable on every tier
 * except ELITE. (An earlier draft of this class also blocked audio downloads outright,
 * tier-independent — that rule was never enabled here and t4targaryen's client does not implement
 * it either; see this app's shared.md, "Overridden for one dev fixture", 2026-08-25.)
 */
@Service
public class RightsService {

	/**
	 * Validates whether the requested intent is allowed for the given tier.
	 *
	 * @throws ApiException with {@link ErrorCode#DOWNLOAD_NOT_PERMITTED} if disallowed.
	 */
	public void check(AccessLevel accessLevel, Intent intent, Format format) {
		if (intent == Intent.DOWNLOAD && accessLevel == AccessLevel.ENTITLED_CONCURRENT) {
			throw new ApiException(
					ErrorCode.DOWNLOAD_NOT_PERMITTED,
					"Downloading is not permitted for concurrent (ELITE) titles. Elite titles are online-only."
			);
		}
	}
}
