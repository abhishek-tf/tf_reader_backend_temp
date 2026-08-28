package com.tf.reader.loan.controller;

import java.time.Duration;
import java.util.Locale;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.loan.dto.BorrowRequest;
import com.tf.reader.loan.dto.BorrowResponse;
import com.tf.reader.loan.dto.LoanPage;
import com.tf.reader.loan.dto.ReturnResponse;
import com.tf.reader.loan.entity.LoanStatus;
import java.time.Clock;

import com.tf.reader.loan.service.BorrowService;
import com.tf.reader.loan.service.BorrowService.BorrowResult;
import com.tf.reader.loan.service.IdempotencyStore;
import com.tf.reader.loan.service.LoanListService;
import com.tf.reader.loan.service.ReturnService;

/**
 * The reader's loans over HTTP (Module B): borrow ({@code POST /loans}, D-024), return
 * ({@code POST /{loanId}/return}, D-022), and the personal-library listing ({@code GET /loans}).
 *
 * <p>Thin by design. Identity is the authenticated {@link CurrentUser} the app resource-server
 * chain resolves from the verified token (invariant #5); we never read a userId from a query
 * param or body. The business rules live in the services.
 */
@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

	private final BorrowService borrowService;
	private final LoanListService loanList;
	private final ReturnService returnService;
	private final IdempotencyStore<BorrowResponse> borrowIdempotency;
	private final IdempotencyStore<ReturnResponse> returnIdempotency;

	public LoanController(BorrowService borrowService, LoanListService loanList,
			ReturnService returnService, Clock clock) {
		this.borrowService = borrowService;
		this.loanList = loanList;
		this.returnService = returnService;
		this.borrowIdempotency = new IdempotencyStore<>(IDEMPOTENCY_TTL, clock);
		this.returnIdempotency = new IdempotencyStore<>(IDEMPOTENCY_TTL, clock);
	}

	/** Borrow a title → 201 created / 200 already held · 403 not entitled · 409 no copies (D-024). */
	@PostMapping
	public ResponseEntity<BorrowResponse> borrow(
			@AuthenticationPrincipal CurrentUser caller,
			@Valid @RequestBody BorrowRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

		if (idempotencyKey != null) {
			var cached = borrowIdempotency.get(caller.userId(), idempotencyKey);
			if (cached.isPresent()) {
				return ResponseEntity.ok(cached.get());
			}
		}

		SubjectRef subject = new SubjectRef(caller.userId(), caller.institutionId());
		BorrowResult result = borrowService.borrow(subject, request.itemId());
		if (idempotencyKey != null) {
			borrowIdempotency.put(caller.userId(), idempotencyKey, result.body());
		}
		return result.created()
				? ResponseEntity.status(HttpStatus.CREATED).body(result.body())
				: ResponseEntity.ok(result.body());
	}

	@GetMapping
	public LoanPage list(
			@AuthenticationPrincipal CurrentUser caller,
			PageQuery page,
			@RequestParam(name = "status", required = false) String status) {

		return loanList.list(caller.userId(), parseStatus(status), page);
	}

	/** Terminate a loan the caller holds → 404 unknown · 403 not theirs · 409 already closed (D-022). */
	@PostMapping("/{loanId}/return")
	public ReturnResponse returnLoan(
			@AuthenticationPrincipal CurrentUser caller,
			@PathVariable String loanId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

		if (idempotencyKey != null) {
			var cached = returnIdempotency.get(caller.userId(), idempotencyKey);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		ReturnResponse response = returnService.returnLoan(caller.userId(), loanId);
		if (idempotencyKey != null) {
			returnIdempotency.put(caller.userId(), idempotencyKey, response);
		}
		return response;
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
