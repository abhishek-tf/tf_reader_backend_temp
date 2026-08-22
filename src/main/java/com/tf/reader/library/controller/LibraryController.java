package com.tf.reader.library.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.library.dto.LibraryResponse;
import com.tf.reader.library.service.LibraryAssembler;
import com.tf.reader.library.support.CurrentReaderResolver;
import com.tf.reader.library.support.ReaderIdentity;

/**
 * HTTP endpoints for a reader's own library.
 *
 * <p><b>No parameters, and that is the security property.</b> The shelf returned is the one
 * belonging to the token — there is nothing on this signature to point it at another reader, so a
 * cross-institution read is impossible rather than merely unlikely.
 *
 * <p>It does not page either. The response is item ids, and wokay's {@code items:batch} caps at 100,
 * so a shelf longer than that wants {@code GET /api/v1/loans} instead.
 */
@RestController
public class LibraryController {

	static final String PATH = "/api/v1/library";

	private final LibraryAssembler assembler;
	private final CurrentReaderResolver currentReader;

	public LibraryController(LibraryAssembler assembler, CurrentReaderResolver currentReader) {
		this.assembler = assembler;
		this.currentReader = currentReader;
	}

	@GetMapping(PATH)
	public LibraryResponse library(Authentication authentication) {
		ReaderIdentity reader = currentReader.require(authentication);
		return assembler.assemble(reader);
	}

}
