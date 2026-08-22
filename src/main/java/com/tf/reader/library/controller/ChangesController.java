package com.tf.reader.library.controller;

import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.library.dto.ChangesResponse;
import com.tf.reader.library.service.ChangeCursor;
import com.tf.reader.library.service.ChangeFeedService;
import com.tf.reader.library.support.CurrentReaderResolver;
import com.tf.reader.library.support.ReaderIdentity;

/**
 * HTTP endpoints for the incremental change feed.
 *
 * <p><b>The path is wrong, and known to be wrong.</b> This feed carries hold promotions, offer
 * lapses and entitlement revocations as well as loan endings, so a hold event arrives on a
 * loan-shaped path. The API Reference proposes {@code GET /api/v1/changes} and keeps this one only
 * because wokay's file already wrote it down. {@link #PATH} is the single line that changes if it
 * moves.
 */
@RestController
public class ChangesController {

	static final String PATH = "/api/v1/loans/changes";

	private final ChangeFeedService changeFeed;
	private final CurrentReaderResolver currentReader;

	public ChangesController(ChangeFeedService changeFeed, CurrentReaderResolver currentReader) {
		this.changeFeed = changeFeed;
		this.currentReader = currentReader;
	}

	/**
	 * @param since the {@code nextCursor} from the previous call. Omitted means everything, which is
	 *              what a client that has stored nothing needs
	 * @param size  1 to 100, defaulting to 20
	 */
	@GetMapping(PATH)
	public ChangesResponse changes(
			Authentication authentication,
			@RequestParam(required = false) String since,
			@RequestParam(required = false) Integer size) {

		ReaderIdentity reader = currentReader.require(authentication);

		// These two are the only client input on this endpoint and therefore the only two things
		// that can be wrong. Rejected here as ApiException rather than left to bean validation:
		// the shared advice turns an ApiException into the one error envelope with a real code,
		// whereas a raw ResponseStatusException would fall to its catch-all and surface as a 500.
		ChangeCursor cursor = badRequestOnReject(() -> ChangeCursor.parse(since));
		int pageSize = badRequestOnReject(() -> ChangeFeedService.requirePageSize(size));

		return changeFeed.changesSince(reader.userId(), cursor, pageSize);
	}

	private static <T> T badRequestOnReject(Supplier<T> parse) {
		try {
			return parse.get();
		}
		catch (IllegalArgumentException rejected) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
		}
	}

}
