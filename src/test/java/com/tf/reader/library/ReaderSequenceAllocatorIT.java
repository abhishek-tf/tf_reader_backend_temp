package com.tf.reader.library;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.tf.reader.library.service.ReaderSequenceAllocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sequence allocator against a real MongoDB.
 *
 * <p>The one property that matters — no two writes for the same reader get the same number — is a
 * property of the database's per-document serialisation, and a mock cannot observe it. A mock will
 * happily report that read-then-write works.
 *
 * <p>Named {@code IT} so it stays out of {@code mvn test}, and
 * {@code disabledWithoutDocker} so it skips cleanly rather than failing on a machine with no Docker.
 * A test held back out of the branch is a test that rots.
 */
@DataMongoTest
@Testcontainers(disabledWithoutDocker = true)
class ReaderSequenceAllocatorIT {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

	@DynamicPropertySource
	static void mongoProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
	}

	@Autowired
	private MongoTemplate mongo;

	/**
	 * Testcontainers can reuse a container between runs, and {@code @DataMongoTest} does not clean
	 * between methods — so without this the counters survive and every absolute assertion below
	 * drifts upward on the second run. Dropping is cheap: the collection is one small document per
	 * reader.
	 */
	@BeforeEach
	void freshCounters() {
		mongo.dropCollection("changeSeq");
	}

	@Test
	@DisplayName("the first sequence for a reader is 1, so 0 can only ever mean the beginning")
	void startsAtOne() {
		ReaderSequenceAllocator allocator = new ReaderSequenceAllocator(mongo);

		assertThat(allocator.next("user_fresh")).isEqualTo(1L);
		assertThat(allocator.next("user_fresh")).isEqualTo(2L);
	}

	@Test
	@DisplayName("sequences are per reader, so one busy reader does not advance another's cursor")
	void isolatedPerReader() {
		ReaderSequenceAllocator allocator = new ReaderSequenceAllocator(mongo);

		allocator.next("user_a");
		allocator.next("user_a");

		// A shared counter would put user_b's first entry at 3, and a client resuming from a stored
		// cursor of 2 would never see it.
		assertThat(allocator.next("user_b")).isEqualTo(1L);
	}

	@Test
	@DisplayName("a hundred simultaneous writes produce a hundred distinct sequences")
	void neverIssuesTheSameSequenceTwice() throws Exception {
		ReaderSequenceAllocator allocator = new ReaderSequenceAllocator(mongo);
		int threads = 100;

		// The latch is the point. Without it the threads start as they are submitted, and the test
		// passes against a read-then-write implementation that would collide under real load.
		CountDownLatch startTogether = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(threads);
		Set<Long> allocated = Collections.synchronizedSet(new HashSet<>());
		Set<String> failures = ConcurrentHashMap.newKeySet();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			IntStream.range(0, threads).forEach(i -> pool.submit(() -> {
				try {
					startTogether.await();
					allocated.add(allocator.next("user_contended"));
				}
				catch (Exception failure) {
					failures.add(failure.toString());
				}
				finally {
					finished.countDown();
				}
			}));

			startTogether.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures).isEmpty();
		// Contiguous as well as distinct. A gap would mean a cursor pointing at nothing, and a
		// duplicate would have been rejected by the unique index rather than shared quietly.
		assertThat(allocated).containsExactlyInAnyOrderElementsOf(
				IntStream.rangeClosed(1, threads).asLongStream().boxed().toList());
	}

}
