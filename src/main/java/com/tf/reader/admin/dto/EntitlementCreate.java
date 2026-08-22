package com.tf.reader.admin.dto;

import java.time.LocalDate;

import com.tf.reader.catalogue.entity.ScopeType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

 //scopeType      what this grant points at
 //scopeId        the publisher, collection or item id, read against scopttype
 //copies         null means unlimited; a number makes the grant copy limited
 //loanPeriodDays defaults to 14 when omitted
 //validFrom      defaults to today when omitted
 //validTo        null means open ended
 
public record EntitlementCreate(
		@NotNull ScopeType scopeType,
		@NotBlank String scopeId,
		@Min(1) Integer copies,
		@Min(1) Integer loanPeriodDays,
		LocalDate validFrom,
		LocalDate validTo) {
}
