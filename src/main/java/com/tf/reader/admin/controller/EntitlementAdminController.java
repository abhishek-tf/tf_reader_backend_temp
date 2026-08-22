package com.tf.reader.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.admin.dto.EntitlementCreate;
import com.tf.reader.admin.dto.EntitlementUpdate;
import com.tf.reader.admin.dto.EntitlementView;
import com.tf.reader.admin.service.EntitlementAdminService;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import jakarta.validation.Valid;

//Below is the controller for entitlement management in the admin API. It provides endpoints to list, create, update, and revoke entitlements for institutions. The controller uses the EntitlementAdminService to perform the necessary operations and returns appropriate responses based on the HTTP methods used.
@RestController("entitlementAdminController")
@RequestMapping("/api/admin/v1")
public class EntitlementAdminController {

	private final EntitlementAdminService entitlements;

	public EntitlementAdminController(EntitlementAdminService entitlements) {
		this.entitlements = entitlements;
	}

	@GetMapping("/institutions/{institutionId}/entitlements")
	public PageResponse<EntitlementView> list(@PathVariable String institutionId, PageQuery pageQuery) {
		return entitlements.list(institutionId, pageQuery);
	}

	@PostMapping("/institutions/{institutionId}/entitlements")
	@ResponseStatus(HttpStatus.CREATED)
	public EntitlementView create(@PathVariable String institutionId, @Valid @RequestBody EntitlementCreate body) {
		return entitlements.create(institutionId, body);
	}

	@PutMapping("/entitlements/{entitlementId}")
	public EntitlementView update(@PathVariable String entitlementId, @Valid @RequestBody EntitlementUpdate body) {
		return entitlements.update(entitlementId, body);
	}

	@DeleteMapping("/entitlements/{entitlementId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String entitlementId) {
		entitlements.revoke(entitlementId);
	}

}
