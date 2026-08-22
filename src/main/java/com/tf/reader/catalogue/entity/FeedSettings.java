package com.tf.reader.catalogue.entity;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Document(collection = "feedSettings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FeedSettings {

	@Id
	private String id;

	@Indexed(unique = true)
	private String institutionId;

	private String feedTitle;
	private int pageSize;
	private String defaultSort;
	private List<Shelf> shelves;
	private Instant updatedAt;

	// For optimistic locking. Defaults to 0 so a first save (nothing stored yet) matches
	// the version 0 the GET default view reports.
	private long version;

}
