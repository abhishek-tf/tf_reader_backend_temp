package com.tf.reader.admin.dto;

/**
 * The Collection schema as the console sees it. Named {@code CollectionView},
 * not {@code Collection}, to stay out of the way of {@code java.util.Collection}.
 *
 * <p>
 * {@code itemCount} is derived on every read - counted from
 * {@code catalogueItems.collectionIds} - and never stored on the entity.
 */
public record CollectionView(String id, String publisherId, String code, String name, String description,
		long itemCount) {
}
