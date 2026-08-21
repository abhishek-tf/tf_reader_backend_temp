package com.tf.reader.loan.controller;

import java.security.Principal;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.loan.dto.LoanPage;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.service.LoanListService;

/**
 * The reader's personal library: {@code GET /api/v1/loans}.
 *
 * <p>Thin by design. Identity is the authenticated principal's name — the token {@code sub} the
 * app resource-server chain already verified (invariant #5); we never read a userId from a query
 * param. Paging is resolved by the shared {@code PageQuery} resolver, and the only work here is
 * turning the {@code ?status=} string into an enum (a bad value is a {@code 400}, not a {@code 500}).
 *
 * <p>No borrow/return endpoints: in the adopted design a licence is created when a reading session
 * opens (D-016), not by a call to this controller.
 */
@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

	private final LoanListService loanList;

	public LoanController(LoanListService loanList) {
		this.loanList = loanList;
	}

	@GetMapping
	public LoanPage list(
			Principal caller,
			PageQuery page,
			@RequestParam(name = "status", required = false) String status) {

		return loanList.list(caller.getName(), parseStatus(status), page);
	}

	/** {@code null}/blank → no filter; an unrecognised value → 400 rather than a silent empty page. */
	private LoanStatus parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return LoanStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"status must be one of ACTIVE, EXPIRED, RETURNED");
		}
	}
}
