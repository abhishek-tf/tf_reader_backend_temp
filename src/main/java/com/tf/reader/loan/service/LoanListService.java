package com.tf.reader.loan.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.tf.reader.common.page.PageQuery;
import com.tf.reader.loan.dto.LoanPage;
import com.tf.reader.loan.dto.LoanResponse;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

/**
 * The personal library listing (Day 8): a reader's own loans, newest first, optionally narrowed by
 * status. The {@code userId} always comes from the caller's token (invariant #5) — this service
 * never takes it from a request parameter.
 */
@Service
public class LoanListService {

	private final LoanRepository loans;
	private final Clock clock;

	public LoanListService(LoanRepository loans, Clock clock) {
		this.loans = loans;
		this.clock = clock;
	}

	/**
	 * @param status the optional {@code ?status=} filter, already parsed; {@code null} means "all".
	 */
	public LoanPage list(String userId, LoanStatus status, PageQuery page) {
		Pageable pageable = PageRequest.of(page.page(), page.size(),
				Sort.by(Sort.Direction.DESC, "borrowedAt"));

		Page<Loan> result = (status == null)
				? loans.findByUserId(userId, pageable)
				: loans.findByUserIdAndStatus(userId, status, pageable);

		List<LoanResponse> items = result.getContent().stream().map(LoanResponse::from).toList();
		return new LoanPage(items, page.page(), page.size(), result.getTotalElements(), clock.instant());
	}
}
