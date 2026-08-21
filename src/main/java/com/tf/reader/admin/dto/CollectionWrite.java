package com.tf.reader.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The CollectionWrite schema. {@code code} only has to be unique within its
 * publisher, checked with {@code BookCollectionRepository.findByPublisherIdAndCode}
 * before save; the compound index on {@code BookCollection} is the backstop.
 */
public record CollectionWrite(
		@NotBlank @Pattern(regexp = "^[a-z0-9-]{2,40}$", message = "code must be lower-case letters, digits and hyphens, 2–40 characters") String code,

		@NotBlank @Size(max = 200) String name,

		@Size(max = 1000) String description) {
}
