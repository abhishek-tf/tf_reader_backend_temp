package com.tf.reader.admin.dto;

import com.tf.reader.admin.entity.AdminRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The AdminUserCreate schema. The scope fields map onto {@code AdminUser.publisherId} and
 * {@code AdminUser.institutionId}; which one is required depends on {@code role}, so that rule is
 * checked in the service rather than here.
 */
@Schema(name = "AdminUserCreate", description = "A new console operator.")
public record AdminUserCreate(

		@Schema(format = "email", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank @Email String email,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank String name,

		@Schema(format = "password", minLength = 12, requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Hashed before storage. Never returned by any endpoint.")
		@NotBlank @Size(min = 12) String password,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull AdminRole role,

		@Schema(description = "Required when role is PUBLISHER_ADMIN, otherwise null.")
		String scopePublisherId,

		@Schema(description = "Required when role is INSTITUTION_ADMIN, otherwise null.")
		String scopeInstitutionId) {
}
