package com.tf.reader.admin.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The FeedSettingsWrite schema. {@code version} is required: this endpoint replaces all
 * three shelves at once, so without it two operators curating at the same time would let
 * the second save silently wipe the first one's work.
 *
 * <p>
 * Shelf-level rules live in {@code FeedSettingsAdminService}, not as annotations on
 * {@link ShelfWrite} — see there for why.
 */
public record FeedSettingsWrite(
		@NotBlank @Size(max = 80) String feedTitle,

		@Min(1) @Max(100) int pageSize,

		String defaultSort,

		@NotNull List<ShelfWrite> shelves,

		@NotNull Long version) {
}
