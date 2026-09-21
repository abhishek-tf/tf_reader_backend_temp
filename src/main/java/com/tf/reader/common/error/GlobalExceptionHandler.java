package com.tf.reader.common.error;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
		ErrorCode code = ex.getCode();
		return respond(code, ex.getMessage(), request, ex);
	}


	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		return respond(ErrorCode.FORBIDDEN_SCOPE, "You do not have permission to perform this action.", request, ex);
	}

	/** Raised by a unique index, e.g. two progress records for the same user + book. */
	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
		return respond(ErrorCode.CODE_TAKEN, duplicateKeyMessage(ex), request, ex);
	}

	/**
	 * Mongo names the violated index in its own error message, which is the only way to tell a
	 * content collision - e.g. two offline devices creating the same highlight span - apart from
	 * any other unique-index violation, without a new error code. sendCreate() on the client
	 * already retries a same-id 409 as a PUT; this code is how it tells that case apart from one
	 * where a different device's record occupies the slot and the client's own id must be dropped
	 * instead.
	 */
	private String duplicateKeyMessage(DuplicateKeyException ex) {
		String raw = String.valueOf(ex.getMostSpecificCause().getMessage());
		if (raw.contains("highlight_span_uk")) {
			return "HIGHLIGHT_LOCATOR_DUPLICATION";
		}
		if (raw.contains("bookmark_locator_uk")) {
			return "BOOKMARK_LOCATOR_DUPLICATION";
		}
		if (raw.contains("institution_scope")) {
			return "An active grant already exists for this institution and scope.";
		}
		return "A record already exists for this scope.";
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {
		String message = ex.getConstraintViolations().stream()
				.map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
				.collect(Collectors.joining(", "));
		return respond(ErrorCode.VALIDATION_FAILED, message, request, ex);
	}

	@ExceptionHandler(PayloadTooLargeException.class)
	public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex,
			HttpServletRequest request) {
		return respond(HttpStatus.CONTENT_TOO_LARGE.value(), ErrorCode.VALIDATION_FAILED, ex.getMessage(), request,
				ex);
	}

	/**
	 * A Redis or MongoDB connection failure, or a Redis command timeout — {@code DataAccessException}
	 * is the one common parent Spring Data gives both stores, so one handler covers a Redis outage
	 * (the hold queue's own storage), a Mongo blip, and everything the {@code data} package throws
	 * that isn't already claimed by a more specific handler above (DuplicateKeyException still wins
	 * there — Spring dispatches to the closest match in the hierarchy, not declaration order).
	 *
	 * <p>Previously fell through to {@link #handleUnexpected}: a reader saw the exact same
	 * INTERNAL_ERROR/"An unexpected error occurred" for a transient infrastructure blip as for a
	 * genuine application bug, with nothing telling the client this one is worth retrying. Logged
	 * at ERROR either way via {@link #logFailure} (503 is below the 500 threshold there, so this
	 * adds an explicit warn here instead — a Redis blip is real and worth seeing in logs, just not
	 * at the "something is broken" volume a 500 implies).
	 */
	@ExceptionHandler(org.springframework.dao.DataAccessException.class)
	public ResponseEntity<ErrorResponse> handleDataAccessFailure(org.springframework.dao.DataAccessException ex,
			HttpServletRequest request) {
		log.warn("Data store failure, path={}", request.getRequestURI(), ex);
		return respond(ErrorCode.SERVICE_UNAVAILABLE,
				"This is taking longer than it should. Please try again in a moment.", request, ex);
	}

	// MaxUploadSizeExceededException (Spring's own request-size ceiling, belt-and-braces behind
	// IngestService's own tier-aware 25MB/100MB checks) is NOT handled with an explicit
	// @ExceptionHandler here: ResponseEntityExceptionHandler's base handleException(...) already
	// claims that exact type, and a second declaration for the same type is an "ambiguous
	// @ExceptionHandler" startup failure, not a silent override. It still ends up VALIDATION_FAILED
	// with its real status preserved, via handleExceptionInternal's statusCode.is4xxClientError()
	// fallback below.

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request, ex);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ErrorCode code;
		String message;

		if (ex instanceof MethodArgumentNotValidException validationEx) {
			code = ErrorCode.VALIDATION_FAILED;
			message = fieldErrorsMessage(validationEx);
		} else if (ex instanceof HttpMessageNotReadableException) {
			code = ErrorCode.VALIDATION_FAILED;
			message = "Request body is malformed.";
		} else if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
			code = ErrorCode.NOT_FOUND;
			message = "No such resource.";
		} else if (statusCode.is4xxClientError()) {
			code = ErrorCode.VALIDATION_FAILED;
			message = "The request could not be processed.";
		} else {
			code = ErrorCode.INTERNAL_ERROR;
			message = "An unexpected error occurred.";
		}

		String traceId = newTraceId();
		String path = path(request);
		logFailure(traceId, statusCode.value(), code, path, ex);
		return ResponseEntity.status(statusCode)
				.body(ErrorResponse.of(statusCode.value(), code, message, path, traceId));
	}

	private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, HttpServletRequest request,
			Exception ex) {
		return respond(code.getStatus().value(), code, message, request, ex);
	}

	/**
	 * Explicit-status overload, for the cases where the status the client sees must differ from
	 * the code's own {@link ErrorCode#getStatus()} - a 413 or a 415 still carries
	 * {@code VALIDATION_FAILED}. Mirrors {@link ErrorResponse}'s own explicit-status overload.
	 */
	private ResponseEntity<ErrorResponse> respond(int status, ErrorCode code, String message,
			HttpServletRequest request, Exception ex) {

		String traceId = newTraceId();
		String path = request.getRequestURI();
		logFailure(traceId, status, code, path, ex);
		return ResponseEntity.status(status).body(ErrorResponse.of(status, code, message, path, traceId));
	}


	private void logFailure(String traceId, int status, ErrorCode code, String path, Exception ex) {
		if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			log.error("Request failed, traceId={} status={} code={} path={}", traceId, status, code, path, ex);
		}
		else {
			log.info("Request failed, traceId={} status={} code={} path={}", traceId, status, code, path);
		}
	}

	private String fieldErrorsMessage(MethodArgumentNotValidException ex) {
		return ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.collect(Collectors.joining(", "));
	}

	private String path(WebRequest request) {
		return ((ServletWebRequest) request).getRequest().getRequestURI();
	}

	private String newTraceId() {
		return Long.toHexString(ThreadLocalRandom.current().nextLong());
	}

}
