package com.tf.reader.admin.dto;

import java.util.List;

/** One curated shelf as the console reads it back. */
public record ShelfView(String id, String title, int order, List<String> itemIds) {
}
