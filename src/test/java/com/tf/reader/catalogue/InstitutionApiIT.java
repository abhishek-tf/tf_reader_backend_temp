package com.tf.reader.catalogue;

// Jackson 3. The HTTP message converters Boot 4 registers produce tools.jackson types, so a
// com.fasterxml JsonNode would not be convertible from a response body.
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9, end to end. Real HTTP over a real port, real MongoDB 7, the real security filter chain,
 * and the Task 8 seed as the data.
 *
 * <p>Two things can only be proven here.
 *
 * <p><b>That no token is needed.</b> A slice test does not load Person D's security configuration,
 * so it cannot fail for the reason that matters. This makes an actual request with no
 * {@code Authorization} header through the real chain, which is what turns "D closed the app's first
 * screen" from a bug found by team1 into a red build in the same hour.
 *
 * <p><b>That the search works in MongoDB's engine.</b> The unit test evaluates the pattern with
 * Java's {@code Pattern}. This one sends it to mongod, which is PCRE2, and asserts that
 * {@code q=impe} really does return Imperial.
 *
 * <p>Task 8 and Task 9 verify each other here: the seed is the only data, so a wrong institution
 * record fails these tests, and this endpoint is the cheapest proof the seed wrote correct records.
 *
 * <p>Needs Docker. If Docker is unavailable this is skipped, and a skipped run must be reported as
 * skipped, never as passing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class InstitutionApiIT {

    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("tnf.seed.enabled", () -> "true");
        // tf.catalogue.base-url comes from the test-wide default in application.properties -
        // see the comment there for why it's fixed rather than tied to the random test port.
    }

    @Autowired TestRestTemplate http;

    private ResponseEntity<JsonNode> get(String path) {
        return http.getForEntity(path, JsonNode.class);
    }

    // ------------------------------------------------------------------------------- the D guard

    @Test
    @DisplayName("both endpoints answer with no Authorization header at all")
    void noTokenIsRequired() {
        assertThat(get("/api/v1/institutions").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/institutions/inst_7f3").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a token that happens to be present is ignored rather than validated")
    void tokensAreIgnoredNotRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer not.a.real.token");

        ResponseEntity<JsonNode> response =
                http.exchange(
                        "/api/v1/institutions", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        // A garbage token must not turn the find-your-institution screen into a 401. If it did, a
        // user with an expired session could not get back to the picker to sign in again.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------------------------- the list

    @Test
    @DisplayName("only ACTIVE institutions appear, sorted by name ascending")
    void listExcludesSuspendedAndIsSorted() {
        JsonNode body = get("/api/v1/institutions").getBody();

        List<String> names = new ArrayList<>();
        body.get("items").forEach(i -> names.add(i.get("name").asString()));

        // The seed holds Imperial (ACTIVE), UCL (ACTIVE) and Leeds (SUSPENDED).
        assertThat(names).containsExactly("Imperial College London", "University College London");
        assertThat(names).doesNotContain("University of Leeds");
        assertThat(body.get("total").asInt()).as("total counts matches, not all records").isEqualTo(2);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("a list item on the wire carries exactly six keys")
    void listItemLeaksNothing() {
        JsonNode item = get("/api/v1/institutions").getBody().get("items").get(0);

        List<String> keys = new ArrayList<>();
        // properties(), not fieldNames(): Jackson 3 removed the latter.
        item.properties().forEach(e -> keys.add(e.getKey()));
        assertThat(keys).containsExactlyInAnyOrder("id", "code", "name", "country", "city", "branding");

        // type is internal and catalogueVersion is a cache key. Neither has any business here, and
        // this assertion runs against the real serialiser rather than a mocked one.
        assertThat(item.has("type")).isFalse();
        assertThat(item.has("catalogueVersion")).isFalse();
        assertThat(item.has("status")).isFalse();
    }

    @Test
    @DisplayName("q=impe finds Imperial, in MongoDB's own regex engine")
    void partialWordSearchWorksAgainstRealMongo() {
        // The single most likely defect in this endpoint, proven where it actually runs. A $text
        // index would return nothing here, because text indexes match whole words.
        JsonNode body = get("/api/v1/institutions?q=impe").getBody();

        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("id").asString()).isEqualTo("inst_7f3");
    }

    @Test
    @DisplayName("search is case insensitive and trimmed")
    void searchIsCaseInsensitiveAndTrimmed() {
        assertThat(get("/api/v1/institutions?q=IMPE").getBody().get("total").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/institutions?q=%20%20impe%20%20").getBody().get("total").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("search matches any word of the name, but not a fragment mid-word")
    void searchMatchesWordStarts() {
        assertThat(get("/api/v1/institutions?q=college").getBody().get("total").asInt())
                .as("both names contain College")
                .isEqualTo(2);
        assertThat(get("/api/v1/institutions?q=london").getBody().get("total").asInt())
                .as("both names end in London. This is the name, not the city: the contract says name")
                .isEqualTo(2);
        assertThat(get("/api/v1/institutions?q=mperial").getBody().get("total").asInt())
                .as("a fragment starting mid-word does not match")
                .isZero();
    }

    @Test
    @DisplayName("q is name only, so a code or an abbreviation finds nothing")
    void searchDoesNotCoverCodeOrCity() {
        // Straight from the contract: "free text search over the institution name". Worth an explicit
        // test because it is a usability gap, not a bug: a UCL student typing "ucl" gets no results
        // even though that is the institution's code. Raised with team1.
        assertThat(get("/api/v1/institutions?q=ucl").getBody().get("total").asInt())
                .as("ucl is the code, not a word in the name")
                .isZero();
    }

    @Test
    @DisplayName("a regex metacharacter in the search box returns nothing and does not error")
    void hostileSearchTermsAreInert() {
        for (String hostile : List.of(".*", "^", "(", "[a-z]+", "a{1,99}", "\\")) {
            ResponseEntity<JsonNode> response =
                    http.getForEntity("/api/v1/institutions?q={q}", JsonNode.class, hostile);

            // An unescaped "(" is an unbalanced group and makes mongod throw, which would surface as
            // a 500 from a public, unauthenticated endpoint.
            assertThat(response.getStatusCode()).as("q=%s", hostile).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("total").asInt()).as("q=%s", hostile).isZero();
        }
    }

    @Test
    @DisplayName("country is an exact match, case insensitively, and an unknown one is an empty list")
    void countryFilter() {
        assertThat(get("/api/v1/institutions?country=uk").getBody().get("total").asInt()).isEqualTo(2);
        assertThat(get("/api/v1/institutions?country=UK").getBody().get("total").asInt()).isEqualTo(2);

        JsonNode none = get("/api/v1/institutions?country=ZZ").getBody();
        assertThat(none.get("total").asInt()).isZero();
        assertThat(none.get("items").size()).as("an empty array, not a 404").isZero();
    }

    @Test
    @DisplayName("size is echoed when valid, and a page past the end is a 200 with an empty list")
    void paging() {
        assertThat(get("/api/v1/institutions?size=1").getBody().get("size").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/institutions?size=100").getBody().get("size").asInt()).isEqualTo(100);

        ResponseEntity<JsonNode> past = get("/api/v1/institutions?page=99");
        assertThat(past.getStatusCode()).as("past the end is a valid request").isEqualTo(HttpStatus.OK);
        assertThat(past.getBody().get("items").size()).isZero();
        assertThat(past.getBody().get("total").asInt())
                .as("total still counts every match")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("page=-1 and page=abc are both 400 in the shared envelope")
    void badPagingIsRejectedInTheEnvelope() {
        for (String bad : List.of("-1", "abc")) {
            ResponseEntity<JsonNode> response =
                    http.getForEntity("/api/v1/institutions?page={p}", JsonNode.class, bad);

            assertThat(response.getStatusCode()).as("page=%s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getBody().has("traceId")).as("every failure carries one").isTrue();
        }
    }

    @Test
    @DisplayName("size out of range is a 400, not a clamped 200")
    void sizeOutOfRangeIsRejected() {
        for (String bad : List.of("0", "-1", "101", "5000")) {
            ResponseEntity<JsonNode> response =
                    http.getForEntity("/api/v1/institutions?size={s}", JsonNode.class, bad);

            assertThat(response.getStatusCode()).as("size=%s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("message").asString())
                    .isEqualTo("size must be between 1 and 100");
        }
    }

    // --------------------------------------------------------------------------------- the detail

    @Test
    @DisplayName("the detail carries signIn from the record and a server-built catalogueUrl")
    void detailIsComplete() {
        JsonNode body = get("/api/v1/institutions/inst_7f3").getBody();

        List<String> keys = new ArrayList<>();
        body.properties().forEach(e -> keys.add(e.getKey()));
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "id", "code", "name", "country", "city", "branding", "signIn", "catalogueUrl");

        assertThat(body.get("signIn").get("method").asString()).isEqualTo("SAML");
        assertThat(body.get("signIn").get("idpHint").asString())
                .as("read from the record, not a constant")
                .isEqualTo("imperial-saml-mock");
        assertThat(body.get("catalogueUrl").asString())
                .isEqualTo("http://localhost:8080/opds/v1/institutions/inst_7f3/catalogue");
    }

    @Test
    @DisplayName("a suspended institution is a 404, identical to an unknown id")
    void suspendedInstitutionIs404() {
        ResponseEntity<JsonNode> suspended = get("/api/v1/institutions/inst_leeds");
        ResponseEntity<JsonNode> unknown = get("/api/v1/institutions/inst_no_such_thing");

        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Not 403. A 403 would confirm that inst_leeds exists, which lets a stranger enumerate our
        // customers by walking ids. The two bodies must be indistinguishable apart from path.
        assertThat(suspended.getBody().get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(suspended.getBody().get("message").asString())
                .isEqualTo(unknown.getBody().get("message").asString())
                .isEqualTo("No such institution");
    }

    @Test
    @DisplayName("a malformed id is the same 404, not a 400 or a 500")
    void malformedIdIs404() {
        // Ids are prefixed strings, not ObjectIds, so nothing here can throw a conversion error. This
        // asserts that stays true: switching to ObjectId later would turn this into a 500.
        assertThat(get("/api/v1/institutions/!!not-an-id!!").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}