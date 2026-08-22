package com.tf.reader.admin.dto;

import java.util.List;

/**
 * One curated shelf as the console sends it. Shelf-level rules (the id set, order,
 * title, item existence and entitlement) are enforced in {@code FeedSettingsAdminService},
 * not with annotations here — they need repository lookups and cross-shelf checks a
 * single-field validator cannot perform.
 */
public record ShelfWrite(String id, String title, int order, List<String> itemIds) {
}
