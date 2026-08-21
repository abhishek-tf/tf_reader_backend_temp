package com.tf.reader.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.admin.dto.CollectionItemsResult;
import com.tf.reader.admin.dto.CollectionItemsWrite;
import com.tf.reader.admin.dto.CollectionView;
import com.tf.reader.admin.dto.CollectionWrite;
import com.tf.reader.admin.service.CollectionAdminService;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import jakarta.validation.Valid;

/**
 * Collection admin endpoints. Two are nested under the owning publisher
 * because a collection is always created and listed in that context; the
 * membership endpoint is keyed by the collection alone, once it exists.
 *
 * <pre>
 *   GET  /api/admin/v1/publishers/{publisherId}/collections        list
 *   POST /api/admin/v1/publishers/{publisherId}/collections        create, 201
 *   PUT  /api/admin/v1/collections/{collectionId}/items             set membership
 * </pre>
 */
@RestController("collectionAdminController")
@RequestMapping("/api/admin/v1")
public class CollectionAdminController {

	private final CollectionAdminService collections;

	public CollectionAdminController(CollectionAdminService collections) {
		this.collections = collections;
	}

	@GetMapping("/publishers/{publisherId}/collections")
	public PageResponse<CollectionView> list(@PathVariable String publisherId, PageQuery pageQuery) {
		return collections.list(publisherId, pageQuery);
	}

	@PostMapping("/publishers/{publisherId}/collections")
	@ResponseStatus(HttpStatus.CREATED)
	public CollectionView create(@PathVariable String publisherId, @Valid @RequestBody CollectionWrite body) {
		return collections.create(publisherId, body);
	}

	@PutMapping("/collections/{collectionId}/items")
	public CollectionItemsResult setItems(@PathVariable String collectionId,
			@Valid @RequestBody CollectionItemsWrite body) {
		return collections.setItems(collectionId, body);
	}

}
