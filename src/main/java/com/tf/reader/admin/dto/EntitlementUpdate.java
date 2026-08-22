package com.tf.reader.admin.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

//Below is the EntitlementUpdate schema. It includes fields for copies, loan period days, valid from and valid to dates, and a version number. 
public record EntitlementUpdate(
		@Min(1) Integer copies,
		@Min(1) Integer loanPeriodDays,
		LocalDate validFrom,
		LocalDate validTo,
		@NotNull Long version) {
}
