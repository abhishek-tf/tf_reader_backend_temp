package com.tf.reader.admin.dto;

import java.util.List;

/**
 * The FeedSettings schema as the console sees it. {@code catalogueVersion} is the
 * owning institution's cache-invalidation counter, read only and never accepted on
 * write. {@code version} is the optimistic-locking token for this record specifically —
 * a different counter for a different purpose, not to be confused with the one above.
 */
public record FeedSettingsView(
		String institutionId,
		String feedTitle,
		int pageSize,
		String defaultSort,
		List<ShelfView> shelves,
		long catalogueVersion,
		long version) {
}
