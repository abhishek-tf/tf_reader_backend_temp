package com.tf.reader;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The MongoDB and Redis a whole-application test needs, in containers this test run owns.
 *
 * <p>Extend this from every {@code @SpringBootTest} that loads {@link ReaderApplication}, because
 * loading it means creating a {@code MongoTemplate}, and {@code auto-index-creation: true} makes
 * that constructor issue {@code createIndexes} against whatever answers on the configured host.
 * With no configuration the default is {@code localhost:27017} - so a context that never intends to
 * read a document still fails to start, either on a connection timeout when nothing is listening or
 * on <i>error 13, Unauthorized</i> when the {@code docker-compose.yml} MongoDB is up, since that
 * one is started with root credentials and only {@code application-local.yml} knows them.
 *
 * <p>Pointing tests at the developer's own MongoDB would trade that for a worse bargain: the suite
 * would pass or fail depending on which containers happen to be running, and index assertions would
 * be read against data left behind by the last {@code bootRun}. A container per test run costs a few
 * seconds and owes nothing to the machine it runs on.
 *
 * <p><b>The containers are static and are never stopped.</b> Spring caches an application context
 * per configuration, so a build holds several at once; a container tied to one context's lifecycle
 * would be torn down under the others. Started once per JVM and reaped by Testcontainers' Ryuk
 * sidecar on exit, one MongoDB and one Redis serve the entire suite.
 *
 * <p>Redis is here for the same reason as MongoDB rather than because a repository needs it:
 * {@code /actuator/health} aggregates every indicator, and an unreachable Redis makes that endpoint
 * answer 503 - which reads as a failed authorization assertion in tests that only meant to ask
 * whether health is publicly reachable.
 */
public abstract class ContainerisedInfrastructure {

	/**
	 * A signing secret for tests that load the context without caring about tokens.
	 *
	 * <p>{@code tnf.auth.jwt.secret} has no default - deliberately, see {@code application.yml} - so
	 * every context refuses to start until something supplies one, including contexts whose test is
	 * about a MongoDB repository and never mints a token. This is that something. Tests that do
	 * assert on tokens declare their own secret and sign with it.
	 */
	public static final String JWT_SECRET =
			"a-test-only-signing-secret-of-sufficient-length-0123456789";

	/** The database the URI below points at. Named for the real one, to keep queries recognisable. */
	private static final String DATABASE = "tnfreader";

	private static final int REDIS_PORT = 6379;

	// Never closed, on purpose - see the class comment. Ryuk stops both on JVM exit.
	@SuppressWarnings("resource")
	private static final MongoDBContainer MONGODB =
			new MongoDBContainer(DockerImageName.parse("mongo:latest"));

	@SuppressWarnings("resource")
	private static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

	static {
		MONGODB.start();
		REDIS.start();
	}

	/**
	 * Overrides the addresses the application would otherwise default to.
	 *
	 * <p>Inherited by every subclass, and part of the context cache key, so classes that agree on
	 * everything else still share one context rather than starting the application twice.
	 */
	@DynamicPropertySource
	static void containerAddresses(DynamicPropertyRegistry registry) {
		// spring.mongodb, not spring.data.mongodb: Spring Boot 4 split the driver's connection
		// settings out of the Spring Data ones, and spring.data.mongodb.uri now binds to nothing at
		// all - silently, leaving the driver on its localhost:27017 default.
		registry.add("spring.mongodb.uri", () -> MONGODB.getConnectionString() + "/" + DATABASE);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
	}
}
