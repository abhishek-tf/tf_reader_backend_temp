package com.tf.reader.admin.dto;

import com.tf.reader.admin.entity.AdminRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The AdminUserUpdate schema. There is deliberately no {@code email}: it is the operator's identity,
 * and changing it means deactivating and creating. A null {@code password} leaves the stored hash
 * alone, which is why it carries no {@code @NotBlank}.
 */
@Schema(name = "AdminUserUpdate", description = "Changes to an existing console operator.")
public record AdminUserUpdate(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank String name,

		@Schema(format = "password", minLength = 12,
				description = "Omit to leave the password unchanged. Sending it resets it.")
		@Size(min = 12) String password,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull AdminRole role,

		@Schema(description = "Required when role is PUBLISHER_ADMIN, otherwise null.")
		String scopePublisherId,

		@Schema(description = "Required when role is INSTITUTION_ADMIN, otherwise null.")
		String scopeInstitutionId) {
}
