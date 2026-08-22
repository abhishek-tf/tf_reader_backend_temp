package com.tf.reader.admin.dto;

import java.time.LocalDate;

import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ScopeType;


//The Entitlement schema as the console sees it.

public record EntitlementView(
		String id,
		String institutionId,
		ScopeType scopeType,
		String scopeId,
		String scopeLabel,
		boolean copyLimited,
		Integer copies,
		Integer loanPeriodDays,
		LocalDate validFrom,
		LocalDate validTo,
		EntitlementStatus status,
		long resolvedItemCount,
		long version) {
}
