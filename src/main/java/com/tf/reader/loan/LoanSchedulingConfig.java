package com.tf.reader.loan;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler so the loan module's {@code ExpirySweeper} runs (D-023).
 *
 * <p>Kept in the loan module rather than annotating {@code ReaderApplication}, so enabling
 * scheduling is owned by the capability that needs it and does not touch the shared entry point.
 */
@Configuration
@EnableScheduling
public class LoanSchedulingConfig {
}
