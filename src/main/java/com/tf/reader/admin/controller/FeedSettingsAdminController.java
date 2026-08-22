package com.tf.reader.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.admin.dto.FeedSettingsView;
import com.tf.reader.admin.dto.FeedSettingsWrite;
import com.tf.reader.admin.service.FeedSettingsAdminService;

import jakarta.validation.Valid;

/**
 * The three curated shelves for one institution's catalogue feed.
 *
 * <pre>
 *   GET /api/admin/v1/institutions/{institutionId}/feed-settings
 *   PUT /api/admin/v1/institutions/{institutionId}/feed-settings
 * </pre>
 *
 * <p>
 * HTTP only. All rules, including who may call what, live in
 * {@link FeedSettingsAdminService}.
 */
@RestController("feedSettingsAdminController")
@RequestMapping("/api/admin/v1/institutions/{institutionId}/feed-settings")
public class FeedSettingsAdminController {

	private final FeedSettingsAdminService feedSettings;

	public FeedSettingsAdminController(FeedSettingsAdminService feedSettings) {
		this.feedSettings = feedSettings;
	}

	@GetMapping
	public FeedSettingsView get(@PathVariable String institutionId) {
		return feedSettings.get(institutionId);
	}

	@PutMapping
	public FeedSettingsView save(@PathVariable String institutionId, @Valid @RequestBody FeedSettingsWrite body) {
		return feedSettings.save(institutionId, body);
	}

}
