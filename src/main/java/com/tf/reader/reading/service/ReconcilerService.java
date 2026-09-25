package com.tf.reader.reading.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tf.reader.hold.api.LiveOfferQuery;
import com.tf.reader.loan.api.ActiveLoanQuery;

/**
 * Rebuilds Redis lease state from Mongo's own truth — active ELITE loans and live offers.
 *
 * <p>Both sources are needed: a promoted-but-not-yet-accepted offer has no loan, so rebuilding
 * from loans alone would delete every offer's slot mid-promotion.
 */
@Service
public class ReconcilerService {
	private static final Logger log = LoggerFactory.getLogger(ReconcilerService.class);
	private final ActiveLoanQuery loans;
	private final LiveOfferQuery offers;
	private final CopyLeaseImpl lease;
	private final Clock clock;

	public ReconcilerService(ActiveLoanQuery loans, LiveOfferQuery offers, CopyLeaseImpl lease, Clock clock) {
		this.loans = loans;
		this.offers = offers;
		this.lease = lease;
		this.clock = clock;
	}

	/** Full rebuild, every copy-limited item at once — run once the app is ready to serve. */
	@EventListener(ApplicationReadyEvent.class)
	public void reconcileAll() {

		// A startup rebuild must never be able to fail application startup. If Redis isn't up
		// yet (or, in a test context that never provisions it, at all), skip this pass rather
		// than take the whole app down with it - same reasoning as HoldDevDataSeeder.
		try {
			doReconcileAll();
		}
		catch (RedisConnectionFailureException e) {
			log.warn("reconciler: Redis unavailable, skipping startup reconcile ({})", e.getMessage());
		}
	}

	/** Full rebuild returning the number of distinct (scope, itemId) pairs touched. */
	public int reconcileAndCount() {
		return doReconcileAll();
	}

	/** Single-item rebuild returning 1 if the item was touched, 0 if nothing was known for it. */
	public int reconcileAndCount(String itemId) {
		// TODO(flambeau, 2026-W21): scope this to the one item once ActiveLoanQuery/LiveOfferQuery
		// expose per-item reads. Full scan is still correct in the meantime, just unnecessary work.
		return doReconcileAll();
	}

	private int doReconcileAll() {
		Instant now = clock.instant();
		Map<ItemScope, List<LeaseSeed>> seedsByItem = new HashMap<>();

		loans.findAllActiveElite().stream()
				.filter(loan -> loan.leaseId() != null)
				.forEach(loan -> seedsByItem
						.computeIfAbsent(new ItemScope(loan.institutionId(), loan.itemId()), k -> new ArrayList<>())
						.add(new LeaseSeed(loan.leaseId(), loan.dueAt())));

		offers.findAllLiveOffers().forEach(offer -> seedsByItem
				.computeIfAbsent(new ItemScope(offer.scope(), offer.itemId()), k -> new ArrayList<>())
				.add(new LeaseSeed(offer.leaseToken(), offer.expiresAt())));

		// An item with zero live loans or offers still needs a visit: that is exactly the
		// state a fully-lapsed, never-released lease leaves behind, and only rebuild() with
		// an empty seed list purges it.
		lease.knownItems().forEach(known -> seedsByItem
				.computeIfAbsent(new ItemScope(known.scope(), known.itemId()), k -> new ArrayList<>()));

		seedsByItem.forEach((item, seeds) -> lease.rebuild(item.scope(), item.itemId(), seeds, now));
		return seedsByItem.size();
	}

	public void reconcile(String itemId) {
		doReconcileAll();
	}

	private record ItemScope(String scope, String itemId) {
	}
}
