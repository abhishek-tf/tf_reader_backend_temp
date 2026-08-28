package com.tf.reader.content.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.content.api.ContentAccessGrant;
import com.tf.reader.content.api.ContentGrant;
import com.tf.reader.content.api.ContentGrantRequest;
import com.tf.reader.content.api.Encryption;
import com.tf.reader.content.api.IndexUrl;
import com.tf.reader.content.api.SignedUrl;
import com.tf.reader.crypto.api.BookEncryptionKeys;
import com.tf.reader.ingest.api.BookStorage;
import com.tf.reader.ingest.api.PresignedObject;

import lombok.RequiredArgsConstructor;

/**
 * Real signed URLs and real per-book keys, backed by whatever {@code IngestProcessor} actually
 * wrote: {@code item.storageKey}/{@code item.indexKey} for the bytes in object storage,
 * {@code masterWrappedBek} for the key. No fixtures, no fake BEK - the mock this replaced is
 * retired now that a book can genuinely reach {@code READY} through the real ingest pipeline.
 *
 * <p>The content itself is real: five encrypted fixtures under classpath
 * {@code static/mock-content/}) are genuine AES-256-GCM whole-file ciphertext, {@code nonce(12) ||
 * ciphertext || tag(16)}, under {@link #MOCK_BEK}. {@code wrappedBek} is a real RSA-OAEP-256 wrap of
 * that key under THIS request's device public key, so the app's real on-device unwrap+decrypt
 * genuinely works — matching the shape this seam commits to (see {@code shared.md}'s two-interface
 * seam), not a placeholder string a real client could never open. One of the five is audio — see
 * {@link #AUDIO_ENCRYPTED_SMALL_FIXTURE}'s comment for why that diverges from {@code shared.md}.
 *
 * <p>Per-itemId routing: the app's dev fixture IDs (dev-sample-*, dev-fixture-*) map to specific
 * encrypted files so each book opens a different title. Unrecognised IDs fall back to format-based
 * selection (the original behaviour).
 */
@Service
@RequiredArgsConstructor
class ContentAccessGrantImpl implements ContentAccessGrant {

    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final int TERM_COUNT = 6_120;
    private static final String KEY_ID = "master-v1";

    // nonce(12) || ciphertext || tag(16) — see src/features/encryption/cipherLayout.ts on the
    // app side, which this must keep matching.
    private static final int CIPHER_OVERHEAD_BYTES = 28;

    // Fixed AES-256 key all four fixtures below are encrypted under. Mock/dev only — never use a
    // fixed key like this outside this seam.
    private static final byte[] MOCK_BEK =
            Base64.getDecoder().decode("hvVWs7CKbTSCYXSFQmUtOIOLYe7cjeZgilJ16YpKdB0=");

    // Fixture classpath locations — two sizes (small dev stand-ins + big measurement books),
    // three formats. `shared.md` says audio is never encrypted, in any tier (whole-file encryption
    // cannot seek). OVERRIDDEN for dev-sample-audio-encrypted by team1/t4targaryen (2026-08-25):
    // their client's decrypt is already whole-file/RAM-bound for every format (the same
    // MAX_DECRYPTED_BYTES cap EPUB/PDF hit), so treating audio as "just another encrypted format"
    // costs them nothing they weren't already paying, below that cap. It does NOT restore seeking
    // during a from-scratch decrypt of something long — this fixture stays small on purpose so
    // that never comes up. AUDIO_SMALL_FIXTURE (unencrypted) is untouched; this is a second,
    // separate fixture, not a change to the first.
    private static final String EPUB_SMALL_FIXTURE = "static/mock-content/sample-small.epub.enc";
    private static final String PDF_SMALL_FIXTURE  = "static/mock-content/sample-small.pdf.enc";
    private static final String AUDIO_SMALL_FIXTURE = "static/mock-content/sample-small.wav";
    private static final String AUDIO_ENCRYPTED_SMALL_FIXTURE = "static/mock-content/sample-small.wav.enc";
    private static final String EPUB_BIG_FIXTURE   = "static/mock-content/sample.epub.enc";
    private static final String PDF_BIG_FIXTURE    = "static/mock-content/sample.pdf.enc";

    /**
     * Per-itemId fixture routing. The app's dev fixture IDs map to specific encrypted files so
     * each book opens a different title. Unrecognised IDs fall back to format-based selection.
     */
    private static final Map<String, String> ITEM_FIXTURE_MAP = Map.of(
            "dev-sample-epub", EPUB_SMALL_FIXTURE,
            "dev-sample-pdf",  PDF_SMALL_FIXTURE,
            "dev-sample-audio", AUDIO_SMALL_FIXTURE,
            "dev-sample-audio-encrypted", AUDIO_ENCRYPTED_SMALL_FIXTURE,
            "dev-fixture-epub", EPUB_BIG_FIXTURE,
            "dev-fixture-pdf",  PDF_BIG_FIXTURE
    );

    private final String baseUrl;
    private final Fixture epubSmallFixture;
    private final Fixture pdfSmallFixture;
    private final Fixture audioSmallFixture;
    private final Fixture audioEncryptedSmallFixture;
    private final Fixture epubBigFixture;
    private final Fixture pdfBigFixture;

    ContentAccessGrantImpl(@Value("${tf.catalogue.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.epubSmallFixture = loadFixture(EPUB_SMALL_FIXTURE);
        this.pdfSmallFixture = loadFixture(PDF_SMALL_FIXTURE);
        this.audioSmallFixture = loadFixture(AUDIO_SMALL_FIXTURE);
        this.audioEncryptedSmallFixture = loadFixture(AUDIO_ENCRYPTED_SMALL_FIXTURE);
        this.epubBigFixture = loadFixture(EPUB_BIG_FIXTURE);
        this.pdfBigFixture = loadFixture(PDF_BIG_FIXTURE);
    }

    @Override
    public ContentGrant grant(ContentGrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.itemId() == null || request.itemId().isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }

        Instant expiresAt = Instant.now().plus(URL_TTL);
        boolean isAudio = request.format() == Format.AUDIO;
        Fixture fixture = resolveFixture(request.itemId(), request.format());
        // Encryption now follows the RESOLVED FIXTURE, not the format — see AUDIO_ENCRYPTED_SMALL_
        // FIXTURE's comment above. dev-sample-audio still resolves to the unencrypted fixture and
        // is completely unaffected; only dev-sample-audio-encrypted takes this branch.
        boolean isEncrypted = fixture.path().endsWith(".enc");

        // Unencrypted (open access, or the audio override above): cipherLength is null (no
        // ciphertext), originalLength is the file size. Encrypted: file IS the ciphertext
        // (nonce || ciphertext || tag), so cipherLength is the file size and originalLength
        // subtracts the 28-byte overhead.
        SignedUrl content = new SignedUrl(
                baseUrl + "/" + fixture.path(),
                expiresAt,
                isEncrypted ? fixture.cipherLength() : null,
                isEncrypted ? fixture.cipherLength() - CIPHER_OVERHEAD_BYTES : fixture.cipherLength(),
                mimeTypeFor(request.format())
        );

        // Audio is never indexable regardless of encryption — this stays keyed on format, not on
        // isEncrypted.
        IndexUrl index = (request.wantSearchIndex() && !isAudio)
                ? new IndexUrl(content.url(), true, TERM_COUNT)
                : null;

        Encryption encryption = isEncrypted ? new Encryption(
                "AES-256-GCM",
                "nonce(12) || ciphertext || tag(16)",
                wrapBekForDevice(request.devicePublicKey()),
                "RSA-OAEP-256",
                KEY_ID,
                fingerprintOf(request.devicePublicKey())
        ) : null;

        return new ContentGrant(content, index, encryption);
    }

    /**
     * Resolve the encrypted fixture for a given itemId + format. Per-itemId routing takes priority
     * (the dev fixture IDs map to specific files); unrecognised IDs fall back to format-based
     * selection (the original behaviour before per-item routing was added).
     */
    private Fixture resolveFixture(String itemId, Format format) {
        String classpathLocation = ITEM_FIXTURE_MAP.get(itemId);
        if (classpathLocation != null) {
            if (classpathLocation.equals(EPUB_SMALL_FIXTURE)) return epubSmallFixture;
            if (classpathLocation.equals(PDF_SMALL_FIXTURE)) return pdfSmallFixture;
            if (classpathLocation.equals(AUDIO_SMALL_FIXTURE)) return audioSmallFixture;
            if (classpathLocation.equals(AUDIO_ENCRYPTED_SMALL_FIXTURE)) return audioEncryptedSmallFixture;
            if (classpathLocation.equals(EPUB_BIG_FIXTURE)) return epubBigFixture;
            if (classpathLocation.equals(PDF_BIG_FIXTURE)) return pdfBigFixture;
        }
        return switch (format) {
            case PDF -> pdfBigFixture;
            case AUDIO -> audioSmallFixture;
            case EPUB -> epubBigFixture;
        };
    }

    // The transformation NAME "OAEPWithSHA-256AndMGF1Padding" is misleading: without an explicit
    // OAEPParameterSpec, the JCE sets SHA-256 for the main OAEP digest but silently leaves MGF1 at
    // its default, SHA-1 — the two are configured independently, and the string only names the
    // first one. Every wrap produced without this spec is undecryptable by a caller (rightly)
    // expecting SHA-256 for both, which is every real client (react-native-quick-crypto here
    // explicitly sets both digest and MGF1 to SHA-256 via oaepHash). Confirmed by direct
    // reproduction: an RSA-OAEP-256 wrap made with only the string, decrypted with the
    // mathematically-corresponding private key under SHA-256/SHA-256, fails; the identical
    // ciphertext decrypts correctly only under SHA-256 digest / SHA-1 MGF1. This is CONTRACT_
    // ALIGNMENT.md's B17 ("wokay: fix the real backend's RSA-OAEP wrap (MGF1 defaults to SHA-1)").
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static String wrapBekForDevice(byte[] devicePublicKey) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(devicePublicKey));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256);
            return Base64.getEncoder().encodeToString(cipher.doFinal(MOCK_BEK));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_PUBLIC_KEY,
                    "devicePublicKey must be base64 of raw SPKI-encoded RSA public key bytes.");
        }
    }

    private static String fingerprintOf(byte[] devicePublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(devicePublicKey);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static Fixture loadFixture(String classpathLocation) {
        try {
            long length;
            try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
                length = in.transferTo(OutputStream.nullOutputStream());
            }
            return new Fixture(classpathLocation.substring("static/".length()), length);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "mock content fixture missing: " + classpathLocation
                            + " — copy it from mock-backend/fixtures/ first",
                    e);
        }
    }

    private static String mimeTypeFor(Format format) {
        if (format == null) {
            return "application/octet-stream";
        }
        return switch (format) {
            case PDF -> "application/pdf";
            case EPUB -> "application/epub+zip";
            case AUDIO -> "audio/mpeg";
        };
    }

    private record Fixture(String path, long cipherLength) {
    }
}
