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

import com.tf.reader.admin.dto.AdminProfileResponse;
import com.tf.reader.admin.dto.AdminUserCreate;
import com.tf.reader.admin.dto.AdminUserUpdate;
import com.tf.reader.admin.service.AdminUserService;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import jakarta.validation.Valid;

/**
 * Four admin user endpoints.
 *
 * <pre>
 *   GET    /api/admin/v1/admin-users                 list, narrowed to the caller's own scope
 *   POST   /api/admin/v1/admin-users                 create, 201
 *   PUT    /api/admin/v1/admin-users/{adminUserId}   update
 *   DELETE /api/admin/v1/admin-users/{adminUserId}   deactivate, 204
 * </pre>
 *
 * <p>
 * HTTP only. Who may call what, and every scope rule, lives in
 * {@link AdminUserService} - a controller-only check is bypassed the moment a
 * second entry point calls the same service.
 */
@RestController
@RequestMapping("/api/admin/v1/admin-users")
public class AdminUserController {

	private final AdminUserService adminUsers;

	public AdminUserController(AdminUserService adminUsers) {
		this.adminUsers = adminUsers;
	}

	@GetMapping
	public PageResponse<AdminProfileResponse> list(PageQuery pageQuery) {
		return adminUsers.list(pageQuery);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AdminProfileResponse create(@Valid @RequestBody AdminUserCreate body) {
		return adminUsers.create(body);
	}

	@PutMapping("/{adminUserId}")
	public AdminProfileResponse update(@PathVariable String adminUserId, @Valid @RequestBody AdminUserUpdate body) {
		return adminUsers.update(adminUserId, body);
	}

	@DeleteMapping("/{adminUserId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deactivate(@PathVariable String adminUserId) {
		adminUsers.deactivate(adminUserId);
	}

}
