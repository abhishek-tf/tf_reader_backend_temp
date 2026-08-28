# flambeau change log

Every commit touching `auth/`, `loan/`, `hold/`, `reading/`, or `library/` — main or test source — oldest first. Generated from `git log`, not hand-maintained prose; regenerate with the command at the bottom rather than hand-editing history that's already landed.

**How to keep this current:** after every commit that touches one of the five flambeau modules, append one entry in the same shape, newest at the bottom. Format: hash, date, author, subject, then the changed files with their git status letter (A=added, M=modified, D=deleted, R=renamed).

---

## `db9d88e` — 2026-08-13 — Sai Deepak Varanasi
**complete folder structure and pom.xml needed for team flambeau**
Modules: `auth,hold,library,loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/auth/api/SessionQuery.java` |
| A | `src/main/java/com/tf/reader/auth/api/SessionView.java` |
| A | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |
| A | `src/main/java/com/tf/reader/auth/dto/SignInRequest.java` |
| A | `src/main/java/com/tf/reader/auth/dto/TokenResponse.java` |
| A | `src/main/java/com/tf/reader/auth/entity/PersonaType.java` |
| A | `src/main/java/com/tf/reader/auth/entity/ReaderUser.java` |
| A | `src/main/java/com/tf/reader/auth/repository/ReaderUserRepository.java` |
| A | `src/main/java/com/tf/reader/auth/service/OidcMockService.java` |
| A | `src/main/java/com/tf/reader/auth/service/SamlMockService.java` |
| A | `src/main/java/com/tf/reader/auth/service/TokenService.java` |
| A | `src/main/java/com/tf/reader/hold/api/AvailabilityQuery.java` |
| A | `src/main/java/com/tf/reader/hold/api/AvailabilitySnapshot.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldPromotion.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldSnapshot.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldSnapshotQuery.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldView.java` |
| A | `src/main/java/com/tf/reader/hold/api/OfferView.java` |
| A | `src/main/java/com/tf/reader/hold/controller/HoldController.java` |
| A | `src/main/java/com/tf/reader/hold/dto/HoldRequest.java` |
| A | `src/main/java/com/tf/reader/hold/dto/HoldResponse.java` |
| A | `src/main/java/com/tf/reader/hold/entity/Hold.java` |
| A | `src/main/java/com/tf/reader/hold/entity/HoldStatus.java` |
| A | `src/main/java/com/tf/reader/hold/entity/Offer.java` |
| A | `src/main/java/com/tf/reader/hold/repository/HoldRepository.java` |
| A | `src/main/java/com/tf/reader/hold/repository/OfferRepository.java` |
| A | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/HoldPromotionImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |
| A | `src/main/java/com/tf/reader/hold/service/PromotionService.java` |
| A | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| A | `src/main/java/com/tf/reader/library/api/.gitkeep` |
| A | `src/main/java/com/tf/reader/library/controller/ChangesController.java` |
| A | `src/main/java/com/tf/reader/library/controller/LibraryController.java` |
| A | `src/main/java/com/tf/reader/library/dto/ChangesResponse.java` |
| A | `src/main/java/com/tf/reader/library/dto/LibraryResponse.java` |
| A | `src/main/java/com/tf/reader/library/entity/ChangeLogEntry.java` |
| A | `src/main/java/com/tf/reader/library/entity/ChangeReason.java` |
| A | `src/main/java/com/tf/reader/library/repository/ChangeLogRepository.java` |
| A | `src/main/java/com/tf/reader/library/service/ChangeFeedService.java` |
| A | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| A | `src/main/java/com/tf/reader/library/service/OutboxReplayService.java` |
| A | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| A | `src/main/java/com/tf/reader/loan/api/ActiveLoanView.java` |
| A | `src/main/java/com/tf/reader/loan/api/LoanRights.java` |
| A | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| A | `src/main/java/com/tf/reader/loan/dto/BorrowRequest.java` |
| A | `src/main/java/com/tf/reader/loan/dto/LoanResponse.java` |
| A | `src/main/java/com/tf/reader/loan/dto/ReturnRequest.java` |
| A | `src/main/java/com/tf/reader/loan/entity/Loan.java` |
| A | `src/main/java/com/tf/reader/loan/entity/LoanStatus.java` |
| A | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| A | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| A | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |
| A | `src/main/java/com/tf/reader/loan/service/ExpirySweeper.java` |
| A | `src/main/java/com/tf/reader/loan/service/ReturnService.java` |
| A | `src/main/java/com/tf/reader/reading/api/CopyLease.java` |
| A | `src/main/java/com/tf/reader/reading/api/LeaseHandle.java` |
| A | `src/main/java/com/tf/reader/reading/controller/ReadingSessionController.java` |
| A | `src/main/java/com/tf/reader/reading/dto/ReadingSessionRequest.java` |
| A | `src/main/java/com/tf/reader/reading/dto/ReadingSessionResponse.java` |
| A | `src/main/java/com/tf/reader/reading/entity/DeviceFingerprint.java` |
| A | `src/main/java/com/tf/reader/reading/repository/DeviceRepository.java` |
| A | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |
| A | `src/main/java/com/tf/reader/reading/service/LeaseScripts.java` |
| A | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| A | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| A | `src/test/java/com/tf/reader/auth/.gitkeep` |
| A | `src/test/java/com/tf/reader/hold/.gitkeep` |
| A | `src/test/java/com/tf/reader/library/.gitkeep` |
| A | `src/test/java/com/tf/reader/loan/.gitkeep` |
| A | `src/test/java/com/tf/reader/reading/.gitkeep` |

## `667529d` — 2026-08-13 — hemanthb1412
**feat: implement authentication and authorization foundation**
Modules: `auth`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/auth/ApiAuthenticationEntryPoint.java` |
| A | `src/main/java/com/tf/reader/auth/AuthController.java` |
| A | `src/main/java/com/tf/reader/auth/AuthMeResponse.java` |
| A | `src/main/java/com/tf/reader/auth/SamlStartRequest.java` |
| A | `src/main/java/com/tf/reader/auth/SamlStartResponse.java` |
| A | `src/main/java/com/tf/reader/auth/SecurityConfig.java` |
| A | `src/main/java/com/tf/reader/auth/authorization/AuthorizationService.java` |
| A | `src/main/java/com/tf/reader/auth/model/CurrentUser.java` |
| A | `src/main/java/com/tf/reader/auth/model/Institution.java` |
| A | `src/main/java/com/tf/reader/auth/model/Role.java` |
| A | `src/main/java/com/tf/reader/auth/model/TnfUser.java` |
| A | `src/main/java/com/tf/reader/auth/model/UserType.java` |
| A | `src/main/java/com/tf/reader/auth/repository/MockInstitutionRepository.java` |
| A | `src/main/java/com/tf/reader/auth/repository/MockUserRepository.java` |
| A | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationFailureHandler.java` |
| A | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationService.java` |
| A | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandler.java` |
| A | `src/main/java/com/tf/reader/auth/saml/SamlUserMapper.java` |
| A | `src/main/java/com/tf/reader/auth/security/CurrentUserAuthenticationToken.java` |
| A | `src/main/java/com/tf/reader/auth/security/CurrentUserJwtConverter.java` |
| A | `src/main/java/com/tf/reader/auth/security/JwtDecoderConfig.java` |
| A | `src/main/java/com/tf/reader/auth/security/TnfJwtValidator.java` |
| A | `src/main/java/com/tf/reader/auth/token/IssuedToken.java` |
| A | `src/main/java/com/tf/reader/auth/token/JwtProperties.java` |
| A | `src/main/java/com/tf/reader/auth/token/JwtTokenService.java` |
| A | `src/main/java/com/tf/reader/auth/token/TokenService.java` |
| A | `src/main/java/com/tf/reader/auth/transaction/AuthTransaction.java` |
| A | `src/main/java/com/tf/reader/auth/transaction/AuthTransactionStore.java` |
| A | `src/test/java/com/tf/reader/auth/AuthControllerTest.java` |
| A | `src/test/java/com/tf/reader/auth/AuthMeTest.java` |
| A | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |
| A | `src/test/java/com/tf/reader/auth/authorization/AuthorizationServiceTest.java` |
| A | `src/test/java/com/tf/reader/auth/e2e/EndToEndAuthFlowTest.java` |
| A | `src/test/java/com/tf/reader/auth/repository/MockUserRepositoryTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandlerTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlRelyingPartyRegistrationTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlUserMapperTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/CurrentUserJwtConverterTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/JwtAuthenticationTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/SecurityArchitectureTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/SensitiveDataLoggingTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/StatelessApiTest.java` |
| A | `src/test/java/com/tf/reader/auth/security/TnfJwtValidatorTest.java` |
| A | `src/test/java/com/tf/reader/auth/token/JwtPropertiesTest.java` |
| A | `src/test/java/com/tf/reader/auth/token/JwtTokenServiceTest.java` |
| A | `src/test/java/com/tf/reader/auth/transaction/AuthTransactionStoreTest.java` |

## `39669a1` — 2026-08-14 — Sai Deepak Varanasi
**added startert files for finding queue position and also to licence side storing function with  starter files for device fingerprints**
Modules: `hold,loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/api/QueuePlacement.java` |
| A | `src/main/java/com/tf/reader/loan/api/LicenceCommand.java` |
| M | `src/main/java/com/tf/reader/reading/dto/ReadingSessionRequest.java` |
| M | `src/main/java/com/tf/reader/reading/dto/ReadingSessionResponse.java` |
| M | `src/main/java/com/tf/reader/reading/entity/DeviceFingerprint.java` |
| M | `src/main/java/com/tf/reader/reading/repository/DeviceRepository.java` |
| M | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |

## `9a75b7b` — 2026-08-17 — Shashank Kumar Lal
**feat(loan): CAP-4 Foundations — Loan entity, indexes, port seams, error codes**
Modules: `hold,loan,reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/api/HoldPromotion.java` |
| M | `src/main/java/com/tf/reader/hold/service/HoldPromotionImpl.java` |
| A | `src/main/java/com/tf/reader/loan/entity/LicenceModel.java` |
| M | `src/main/java/com/tf/reader/loan/entity/Loan.java` |
| M | `src/main/java/com/tf/reader/loan/entity/LoanStatus.java` |
| M | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| M | `src/main/java/com/tf/reader/reading/api/CopyLease.java` |
| M | `src/main/java/com/tf/reader/reading/api/LeaseHandle.java` |
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/test/java/com/tf/reader/loan/LoanRepositoryTest.java` |

## `0dc8ef9` — 2026-08-17 — Ks-Gupta
**Enhance API contracts and models for availability and hold management**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/api/AvailabilityQuery.java` |
| M | `src/main/java/com/tf/reader/hold/api/AvailabilitySnapshot.java` |
| M | `src/main/java/com/tf/reader/hold/api/HoldPromotion.java` |
| M | `src/main/java/com/tf/reader/hold/api/HoldSnapshot.java` |
| M | `src/main/java/com/tf/reader/hold/api/HoldSnapshotQuery.java` |
| M | `src/main/java/com/tf/reader/hold/api/HoldView.java` |
| M | `src/main/java/com/tf/reader/hold/api/OfferView.java` |

## `cc2860c` — 2026-08-17 — Sai Deepak Varanasi
**Reading broker files with basic implementation**
Modules: `hold,loan,reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/api/AvailabilityQuery.java` |
| M | `src/main/java/com/tf/reader/hold/api/AvailabilitySnapshot.java` |
| D | `src/main/java/com/tf/reader/hold/api/QueuePlacement.java` |
| M | `src/main/java/com/tf/reader/loan/api/LicenceCommand.java` |
| M | `src/main/java/com/tf/reader/reading/api/CopyLease.java` |
| M | `src/main/java/com/tf/reader/reading/api/LeaseHandle.java` |
| M | `src/main/java/com/tf/reader/reading/controller/ReadingSessionController.java` |
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| M | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |

## `27ed272` — 2026-08-17 — Sai Deepak Varanasi
**records for licence and rights service**
Modules: `loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/loan/api/LicenceView.java` |
| A | `src/main/java/com/tf/reader/reading/service/RightsService.java` |

## `8782156` — 2026-08-18 — copilot-swe-agent[bot]
**Merge origin/main into khushi/hold**
Modules: `?`

| | File |
|---|---|

## `c996d31` — 2026-08-18 — Sai Deepak Varanasi
**Merge: resolve availability contract conflicts; unify AvailabilitySnapshot and handle nullable queue length in broker**
Modules: `?`

| | File |
|---|---|

## `78dde76` — 2026-08-18 — Sai Deepak Varanasi
**Added Session Control Files(just starter files) for the application**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/api/SessionQuery.java` |
| M | `src/main/java/com/tf/reader/auth/api/SessionView.java` |

## `b446c98` — 2026-08-18 — Ashwin Sudhakar
**renamed auth/SecurityConfig -> UserSecurityConfig; Updated application.yml**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/AuthController.java` |
| R099 | `src/main/java/com/tf/reader/auth/UserSecurityConfig.java` |
| M | `src/test/java/com/tf/reader/auth/AuthControllerTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |

## `1e53c01` — 2026-08-18 — Sai Deepak Varanasi
**resolved merge conflict - updated UserSecurityConfig, AvailabilityQueryImpl, BorrowService**
Modules: `auth,hold,loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/UserSecurityConfig.java` |
| M | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| M | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |

## `b27a2f0` — 2026-08-18 — Shashank Kumar Lal
**service implementations**
Modules: `auth,hold,loan,reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/SecurityConfig.java` |
| M | `src/main/java/com/tf/reader/auth/security/JwtDecoderConfig.java` |
| M | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| M | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| R062 | `src/main/java/com/tf/reader/loan/api/LicenseCommand.java` |
| R070 | `src/main/java/com/tf/reader/loan/api/LicenseView.java` |
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| M | `src/main/java/com/tf/reader/loan/dto/BorrowRequest.java` |
| M | `src/main/java/com/tf/reader/loan/dto/LoanResponse.java` |
| R097 | `src/main/java/com/tf/reader/loan/entity/LicenseModel.java` |
| M | `src/main/java/com/tf/reader/loan/entity/Loan.java` |
| M | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |
| A | `src/main/java/com/tf/reader/loan/service/LicenseCommandImpl.java` |
| M | `src/main/java/com/tf/reader/reading/dto/ReadingSessionResponse.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| A | `src/test/java/com/tf/reader/loan/BorrowEndpointTest.java` |
| A | `src/test/java/com/tf/reader/loan/BorrowServiceTest.java` |
| M | `src/test/java/com/tf/reader/loan/LoanRepositoryTest.java` |
| A | `src/test/java/com/tf/reader/loan/TokenGeneratorTest.java` |

## `fb907df` — 2026-08-18 — Sai Deepak Varanasi
**Added basic bearer token validator and also modified exception handler to handle session token changes**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/AuthController.java` |
| M | `src/main/java/com/tf/reader/auth/UserSecurityConfig.java` |
| D | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |

## `e38ebe4` — 2026-08-20 — Sai Deepak Varanasi
**Reading Module Architecture & Features, tested with api endpoint using postman only.**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/main/java/com/tf/reader/reading/service/RightsService.java` |

## `75fe3bb` — 2026-08-20 — Sai Deepak Varanasi
**test files for reading module and device concurrency**
Modules: `reading`

| | File |
|---|---|
| A | `src/test/java/com/tf/reader/reading/service/DeviceCapServiceTest.java` |
| A | `src/test/java/com/tf/reader/reading/service/ReadBrokerServiceTest.java` |
| A | `src/test/java/com/tf/reader/reading/service/RightsServiceTest.java` |

## `8fc03f8` — 2026-08-20 — hariii-1122
**feat(library): add change feed API contract**
Modules: `library`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/library/api/ChangeLog.java` |
| A | `src/main/java/com/tf/reader/library/api/ChangeReason.java` |
| A | `src/main/java/com/tf/reader/library/api/ChangeRecord.java` |
| D | `src/main/java/com/tf/reader/library/entity/ChangeReason.java` |
| A | `src/test/java/com/tf/reader/library/ChangeRecordTest.java` |

## `3fe0353` — 2026-08-20 — hariii-1122
**feat(library): add the changeLog collection and its repository**
Modules: `library`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/library/entity/ChangeLogEntry.java` |
| M | `src/main/java/com/tf/reader/library/repository/ChangeLogRepository.java` |

## `742bb57` — 2026-08-20 — AkshayVAthreya
**resolved GlobalExceptionHandler**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/ApiAuthenticationEntryPoint.java` |
| M | `src/main/java/com/tf/reader/auth/AuthController.java` |
| M | `src/main/java/com/tf/reader/auth/authorization/AuthorizationService.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationFailureHandler.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationService.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandler.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlUserMapper.java` |
| R089 | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |

## `8772985` — 2026-08-20 — AkshayVAthreya
**resolved test cases errors and failures**
Modules: `auth`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/auth/AuthControllerTest.java` |
| M | `src/test/java/com/tf/reader/auth/AuthMeTest.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/EndToEndAuthFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlUserMapperTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/JwtAuthenticationTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SecurityArchitectureTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SensitiveDataLoggingTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/StatelessApiTest.java` |

## `b8250e8` — 2026-08-20 — Sai Deepak Varanasi
**solving merge conflicts with wokay team branch**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/UserSecurityConfig.java` |

## `0786142` — 2026-08-20 — Sai Deepak Varanasi
**removed security config**
Modules: `auth`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/auth/UserSecurityConfig.java` |

## `bd4702a` — 2026-08-20 — Sai Deepak Varanasi
**Merge branch 'abhishek-tf:main' into main**
Modules: `?`

| | File |
|---|---|

## `ca08e5e` — 2026-08-20 — Sai Deepak Varanasi
**changed all the imports to match the common folder exceptions and error functions**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/main/java/com/tf/reader/reading/service/RightsService.java` |
| M | `src/test/java/com/tf/reader/reading/service/ReadBrokerServiceTest.java` |
| M | `src/test/java/com/tf/reader/reading/service/RightsServiceTest.java` |

## `b594aa1` — 2026-08-20 — Ks-Gupta
**Add Hold, HoldStatus, and Offer - the document for a queued hold**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/entity/Hold.java` |
| M | `src/main/java/com/tf/reader/hold/entity/HoldStatus.java` |
| M | `src/main/java/com/tf/reader/hold/entity/Offer.java` |
| A | `src/test/java/com/tf/reader/hold/entity/HoldTest.java` |

## `41fcd3f` — 2026-08-20 — Ks-Gupta
**Add HoldRepository - the Mongo lookups join and read-my-holds use**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/repository/HoldRepository.java` |
| A | `src/test/java/com/tf/reader/hold/HoldContainerTest.java` |
| A | `src/test/java/com/tf/reader/hold/repository/HoldRepositoryIT.java` |

## `ce0ca30` — 2026-08-20 — Ks-Gupta
**Add QueueKeys - every Redis key the queue touches, built in one place**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/service/QueueKeys.java` |
| A | `src/test/java/com/tf/reader/hold/service/QueueKeysTest.java` |

## `8e44e0f` — 2026-08-20 — Ks-Gupta
**Add QueueService.join() and holdsFor() - the queue engine**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| A | `src/test/java/com/tf/reader/hold/service/QueueServiceTest.java` |

## `594c941` — 2026-08-20 — Ks-Gupta
**Add HoldRequest and HoldResponse - wire shapes for join and read**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/dto/HoldRequest.java` |
| M | `src/main/java/com/tf/reader/hold/dto/HoldResponse.java` |
| A | `src/test/java/com/tf/reader/hold/dto/HoldRequestTest.java` |
| A | `src/test/java/com/tf/reader/hold/dto/HoldResponseTest.java` |

## `2221765` — 2026-08-20 — Ks-Gupta
**Add HoldController - POST and GET /api/v1/holds**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/controller/HoldController.java` |
| A | `src/test/java/com/tf/reader/hold/controller/HoldControllerTest.java` |

## `7cc1e5d` — 2026-08-20 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `cd0f6f1` — 2026-08-20 — Shashank Kumar Lal
**CAP-4 Day 8: loan listing (GET /api/v1/loans) and active-loan query**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanView.java` |
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| A | `src/main/java/com/tf/reader/loan/dto/LoanPage.java` |
| M | `src/main/java/com/tf/reader/loan/dto/LoanResponse.java` |
| M | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| M | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| A | `src/main/java/com/tf/reader/loan/service/LoanListService.java` |
| A | `src/test/java/com/tf/reader/loan/ActiveLoanQueryTest.java` |
| A | `src/test/java/com/tf/reader/loan/AppTokenGeneratorTest.java` |
| A | `src/test/java/com/tf/reader/loan/BorrowServiceTest.java` |
| A | `src/test/java/com/tf/reader/loan/LoanListEndpointTest.java` |

## `bc8a611` — 2026-08-20 — Shashank Kumar Lal
**Scope branch to CAP-4 loan work: revert unrelated files to main**
Modules: `auth,hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/security/JwtDecoderConfig.java` |
| M | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |

## `17afbb9` — 2026-08-20 — SHASHANK KUMAR LAL
**Merge pull request #8 from Deepu1004/feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `97fa6fe` — 2026-08-21 — hemanthb1412
**week2**
Modules: `auth`

| | File |
|---|---|
| R094 | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |
| R097 | `src/main/java/com/tf/reader/auth/dto/AuthMeResponse.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcAuthenticationService.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcController.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcProperties.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcStartRequest.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcStartResponse.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcTokenClient.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcTokenResponse.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcTransaction.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcTransactionStore.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/client/OidcUserMapper.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/config/MockOidcComponent.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/config/MockOidcConfig.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/config/MockOidcProperties.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/controller/MockOidcController.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/model/MockOidcUser.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/security/MockOidcKeyService.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/service/MockOidcAuthorizationService.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/service/MockOidcTokenService.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/mock/store/MockAuthorizationCodeStore.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/validation/OidcIdTokenDecoder.java` |
| A | `src/main/java/com/tf/reader/auth/oidc/validation/OidcIdTokenValidator.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationService.java` |
| R095 | `src/main/java/com/tf/reader/auth/saml/SamlStartRequest.java` |
| R096 | `src/main/java/com/tf/reader/auth/saml/SamlStartResponse.java` |
| R092 | `src/main/java/com/tf/reader/auth/saml/transaction/AuthTransaction.java` |
| R098 | `src/main/java/com/tf/reader/auth/saml/transaction/AuthTransactionStore.java` |
| R098 | `src/main/java/com/tf/reader/auth/security/ApiAuthenticationEntryPoint.java` |
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |
| D | `src/main/java/com/tf/reader/auth/service/OidcMockService.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |
| R097 | `src/test/java/com/tf/reader/auth/controller/AuthControllerTest.java` |
| R098 | `src/test/java/com/tf/reader/auth/controller/AuthMeTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/EndToEndAuthFlowTest.java` |
| A | `src/test/java/com/tf/reader/auth/e2e/OidcEndToEndAuthFlowTest.java` |
| A | `src/test/java/com/tf/reader/auth/oidc/client/OidcTransactionStoreTest.java` |
| A | `src/test/java/com/tf/reader/auth/oidc/client/OidcUserMapperTest.java` |
| A | `src/test/java/com/tf/reader/auth/oidc/mock/MockOidcProviderTest.java` |
| A | `src/test/java/com/tf/reader/auth/oidc/validation/OidcIdTokenValidationTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandlerTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| A | `src/test/java/com/tf/reader/auth/saml/SamlSecurityEdgeCaseTest.java` |
| R079 | `src/test/java/com/tf/reader/auth/saml/transaction/AuthTransactionStoreTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SecurityArchitectureTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SensitiveDataLoggingTest.java` |

## `b5150d2` — 2026-08-21 — Sai Deepak Varanasi
**final changes for week 2 api endpoint approvals and mock endpoints ready**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |
| M | `src/main/java/com/tf/reader/auth/token/JwtTokenService.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |

## `f9672a8` — 2026-08-21 — hariii-1122
**Add personal library sync and change feed implementation**
Modules: `auth,library`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |
| M | `src/main/java/com/tf/reader/library/controller/ChangesController.java` |
| M | `src/main/java/com/tf/reader/library/controller/LibraryController.java` |
| A | `src/main/java/com/tf/reader/library/dto/ChangeEntryView.java` |
| M | `src/main/java/com/tf/reader/library/dto/ChangesResponse.java` |
| A | `src/main/java/com/tf/reader/library/dto/LibraryHold.java` |
| A | `src/main/java/com/tf/reader/library/dto/LibraryLoan.java` |
| A | `src/main/java/com/tf/reader/library/dto/LibraryOffer.java` |
| M | `src/main/java/com/tf/reader/library/dto/LibraryResponse.java` |
| A | `src/main/java/com/tf/reader/library/service/ChangeCursor.java` |
| M | `src/main/java/com/tf/reader/library/service/ChangeFeedService.java` |
| A | `src/main/java/com/tf/reader/library/service/ChangeLogWriter.java` |
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| A | `src/main/java/com/tf/reader/library/service/ReaderSequenceAllocator.java` |
| A | `src/main/java/com/tf/reader/library/support/CurrentReaderResolver.java` |
| A | `src/main/java/com/tf/reader/library/support/ReaderIdentity.java` |
| A | `src/test/java/com/tf/reader/library/ChangeCursorTest.java` |
| A | `src/test/java/com/tf/reader/library/ChangeFeedServiceTest.java` |
| A | `src/test/java/com/tf/reader/library/ChangeLogWriterTest.java` |
| A | `src/test/java/com/tf/reader/library/CurrentReaderResolverTest.java` |
| A | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |
| A | `src/test/java/com/tf/reader/library/LibraryEndpointsWebTest.java` |
| A | `src/test/java/com/tf/reader/library/ReaderSequenceAllocatorIT.java` |

## `08e0971` — 2026-08-21 — Ks-Gupta
**Address code review: unique holdId index, fail loud on missing Redis data**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/entity/Hold.java` |
| M | `src/main/java/com/tf/reader/hold/service/QueueKeys.java` |
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |

## `514a957` — 2026-08-21 — Ks-Gupta
**Merge remote-tracking branch 'origin/main' into khushi/hold**
Modules: `?`

| | File |
|---|---|

## `fa27b7d` — 2026-08-21 — hariii-1122
**resolving merge conflicts to remove the divergent branch**
Modules: `hold,loan,reading`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/hold/api/AvailabilityQuery.java` |
| D | `src/main/java/com/tf/reader/hold/api/AvailabilitySnapshot.java` |
| D | `src/main/java/com/tf/reader/hold/api/HoldPromotion.java` |
| D | `src/main/java/com/tf/reader/hold/api/HoldSnapshot.java` |
| D | `src/main/java/com/tf/reader/hold/api/HoldSnapshotQuery.java` |
| D | `src/main/java/com/tf/reader/hold/api/HoldView.java` |
| D | `src/main/java/com/tf/reader/hold/api/OfferView.java` |
| D | `src/main/java/com/tf/reader/hold/controller/HoldController.java` |
| D | `src/main/java/com/tf/reader/hold/dto/HoldRequest.java` |
| D | `src/main/java/com/tf/reader/hold/dto/HoldResponse.java` |
| D | `src/main/java/com/tf/reader/hold/entity/Hold.java` |
| D | `src/main/java/com/tf/reader/hold/entity/HoldStatus.java` |
| D | `src/main/java/com/tf/reader/hold/entity/Offer.java` |
| D | `src/main/java/com/tf/reader/hold/repository/HoldRepository.java` |
| D | `src/main/java/com/tf/reader/hold/repository/OfferRepository.java` |
| D | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| D | `src/main/java/com/tf/reader/hold/service/HoldPromotionImpl.java` |
| D | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |
| D | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |
| D | `src/main/java/com/tf/reader/hold/service/PromotionService.java` |
| D | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| D | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| D | `src/main/java/com/tf/reader/loan/api/ActiveLoanView.java` |
| D | `src/main/java/com/tf/reader/loan/api/LicenceCommand.java` |
| D | `src/main/java/com/tf/reader/loan/api/LicenceView.java` |
| D | `src/main/java/com/tf/reader/loan/api/LoanRights.java` |
| D | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| D | `src/main/java/com/tf/reader/loan/dto/BorrowRequest.java` |
| D | `src/main/java/com/tf/reader/loan/dto/LoanResponse.java` |
| D | `src/main/java/com/tf/reader/loan/dto/ReturnRequest.java` |
| D | `src/main/java/com/tf/reader/loan/entity/LicenceModel.java` |
| D | `src/main/java/com/tf/reader/loan/entity/Loan.java` |
| D | `src/main/java/com/tf/reader/loan/entity/LoanStatus.java` |
| D | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| D | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| D | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |
| D | `src/main/java/com/tf/reader/loan/service/ExpirySweeper.java` |
| D | `src/main/java/com/tf/reader/loan/service/ReturnService.java` |
| D | `src/main/java/com/tf/reader/reading/api/CopyLease.java` |
| D | `src/main/java/com/tf/reader/reading/api/LeaseHandle.java` |
| D | `src/main/java/com/tf/reader/reading/controller/ReadingSessionController.java` |
| D | `src/main/java/com/tf/reader/reading/dto/ReadingSessionRequest.java` |
| D | `src/main/java/com/tf/reader/reading/dto/ReadingSessionResponse.java` |
| D | `src/main/java/com/tf/reader/reading/entity/DeviceFingerprint.java` |
| D | `src/main/java/com/tf/reader/reading/repository/DeviceRepository.java` |
| D | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| D | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |
| D | `src/main/java/com/tf/reader/reading/service/LeaseScripts.java` |
| D | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| D | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| D | `src/main/java/com/tf/reader/reading/service/RightsService.java` |

## `30fe805` — 2026-08-21 — hariii-1122
**Merge branch 'main' of https://github.com/Deepu1004/tf_reader_backend_temp into reader_week_2**
Modules: `?`

| | File |
|---|---|

## `1d50b89` — 2026-08-21 — hariii-1122
**library sync for holds and cursor**
Modules: `auth,hold,loan,reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |
| A | `src/main/java/com/tf/reader/hold/api/AvailabilityQuery.java` |
| A | `src/main/java/com/tf/reader/hold/api/AvailabilitySnapshot.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldPromotion.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldSnapshot.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldSnapshotQuery.java` |
| A | `src/main/java/com/tf/reader/hold/api/HoldView.java` |
| A | `src/main/java/com/tf/reader/hold/api/OfferView.java` |
| A | `src/main/java/com/tf/reader/hold/repository/OfferRepository.java` |
| A | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/HoldPromotionImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |
| A | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |
| A | `src/main/java/com/tf/reader/hold/service/PromotionService.java` |
| A | `src/main/java/com/tf/reader/loan/api/LicenceCommand.java` |
| A | `src/main/java/com/tf/reader/loan/api/LicenceView.java` |
| A | `src/main/java/com/tf/reader/loan/api/LoanRights.java` |
| A | `src/main/java/com/tf/reader/loan/dto/BorrowRequest.java` |
| A | `src/main/java/com/tf/reader/loan/dto/ReturnRequest.java` |
| A | `src/main/java/com/tf/reader/loan/entity/LicenceModel.java` |
| A | `src/main/java/com/tf/reader/loan/entity/Loan.java` |
| A | `src/main/java/com/tf/reader/loan/entity/LoanStatus.java` |
| A | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |
| A | `src/main/java/com/tf/reader/loan/service/ExpirySweeper.java` |
| A | `src/main/java/com/tf/reader/loan/service/ReturnService.java` |
| A | `src/main/java/com/tf/reader/reading/api/CopyLease.java` |
| A | `src/main/java/com/tf/reader/reading/api/LeaseHandle.java` |
| A | `src/main/java/com/tf/reader/reading/controller/ReadingSessionController.java` |
| A | `src/main/java/com/tf/reader/reading/dto/ReadingSessionRequest.java` |
| A | `src/main/java/com/tf/reader/reading/dto/ReadingSessionResponse.java` |
| A | `src/main/java/com/tf/reader/reading/entity/DeviceFingerprint.java` |
| A | `src/main/java/com/tf/reader/reading/repository/DeviceRepository.java` |
| A | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/main/java/com/tf/reader/reading/service/DeviceCapService.java` |
| A | `src/main/java/com/tf/reader/reading/service/LeaseScripts.java` |
| A | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| A | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| A | `src/main/java/com/tf/reader/reading/service/RightsService.java` |

## `2aa3439` — 2026-08-21 — Shashank Kumar Lal
**Fix decision citation in loan comments: D-016 -> D-020**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| M | `src/test/java/com/tf/reader/loan/BorrowServiceTest.java` |

## `6c63aa9` — 2026-08-21 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `f5dae48` — 2026-08-21 — Sai Deepak Varanasi
**modified individual university repo and also added mock repo's**
Modules: `auth`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/repository/MockInstitutionRepository.java` |

## `b0d1943` — 2026-08-21 — Shashank Kumar Lal
**Align loan endpoint to unified app auth (CurrentUser + reader-auth token)**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| M | `src/test/java/com/tf/reader/loan/LoanListEndpointTest.java` |

## `7998e46` — 2026-08-21 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `303b188` — 2026-08-21 — Shashank Kumar Lal
**Add MockLoanRepository with seeded loan fixtures (matches auth mock pattern)**
Modules: `loan`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/loan/repository/MockLoanRepository.java` |
| A | `src/test/java/com/tf/reader/loan/repository/MockLoanRepositoryTest.java` |

## `7f81534` — 2026-08-21 — Ks-Gupta
**Add MockHoldFixtures - seeded reference for testing join-the-queue**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/repository/MockHoldFixtures.java` |

## `685b9f9` — 2026-08-21 — hariii-1122
**feat(library): add mock library data**
Modules: `library`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/library/repository/MockLibraryRepository.java` |
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `d07269b` — 2026-08-21 — Ks-Gupta
**Merge remote-tracking branch 'origin/main' into khushi/hold**
Modules: `?`

| | File |
|---|---|

## `b95d531` — 2026-08-21 — Sai Deepak Varanasi
**Merge pull request #13 from Deepu1004/reader_week_2**
Modules: `?`

| | File |
|---|---|

## `04243d7` — 2026-08-22 — Shashank Kumar Lal
**CAP-4 Module B: borrow, return, and expiry for the loan lifecycle**
Modules: `loan`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/loan/LoanSchedulingConfig.java` |
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| M | `src/main/java/com/tf/reader/loan/dto/BorrowRequest.java` |
| A | `src/main/java/com/tf/reader/loan/dto/BorrowResponse.java` |
| A | `src/main/java/com/tf/reader/loan/dto/ReturnResponse.java` |
| M | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |
| M | `src/main/java/com/tf/reader/loan/service/ExpirySweeper.java` |
| M | `src/main/java/com/tf/reader/loan/service/ReturnService.java` |
| A | `src/test/java/com/tf/reader/loan/BorrowFlowTest.java` |
| M | `src/test/java/com/tf/reader/loan/BorrowServiceTest.java` |
| A | `src/test/java/com/tf/reader/loan/ExpirySweeperTest.java` |
| A | `src/test/java/com/tf/reader/loan/ReturnServiceTest.java` |

## `d993808` — 2026-08-22 — Sai Deepak Varanasi
**fix(auth): unify JWT minting configuration and resolve security test failures**
Modules: `auth`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/auth/ApiAuthenticationEntryPoint.java` |
| M | `src/main/java/com/tf/reader/auth/security/JwtDecoderConfig.java` |
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |
| M | `src/main/java/com/tf/reader/auth/token/JwtTokenService.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |
| M | `src/test/java/com/tf/reader/auth/controller/AuthControllerTest.java` |
| M | `src/test/java/com/tf/reader/auth/controller/AuthMeTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/EndToEndAuthFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandlerTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/JwtAuthenticationTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SensitiveDataLoggingTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/StatelessApiTest.java` |
| M | `src/test/java/com/tf/reader/auth/token/JwtTokenServiceTest.java` |

## `23d2c10` — 2026-08-22 — Sai Deepak Varanasi
**fix(auth): unify JWT minting configuration and resolve security test failures**
Modules: `auth,loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |
| M | `src/main/java/com/tf/reader/auth/oidc/client/OidcTransaction.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationService.java` |
| R092 | `src/main/java/com/tf/reader/auth/transaction/AuthTransaction.java` |
| R098 | `src/main/java/com/tf/reader/auth/transaction/AuthTransactionStore.java` |
| M | `src/test/java/com/tf/reader/auth/controller/AuthControllerTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/OidcEndToEndAuthFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandlerTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlSecurityEdgeCaseTest.java` |
| R099 | `src/test/java/com/tf/reader/auth/transaction/AuthTransactionStoreTest.java` |
| M | `src/test/java/com/tf/reader/loan/LoanListEndpointTest.java` |

## `7641deb` — 2026-08-22 — Sai Deepak Varanasi
**Rewritten test files for auth to correct the test file failures and errors**
Modules: `auth`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/auth/ApiAuthenticationEntryPoint.java` |
| M | `src/main/java/com/tf/reader/auth/security/UserSecurityConfig.java` |

## `d2de96f` — 2026-08-23 — Shashank Kumar Lal
**CAP-4 D-025: add findAllFor(userId) to ActiveLoanQuery for Module E**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| M | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| M | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| M | `src/test/java/com/tf/reader/loan/ActiveLoanQueryTest.java` |

## `87fcd4a` — 2026-08-24 — Ks-Gupta
**Add HoldProperties - offer window, sweep interval, lease slack, lock TTL, config-driven**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/service/HoldProperties.java` |
| A | `src/test/java/com/tf/reader/hold/service/HoldPropertiesTest.java` |

## `8031837` — 2026-08-24 — Ks-Gupta
**Add HoldWrites - guarded findAndModify/findAndRemove ops for the hold lifecycle**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/repository/HoldWrites.java` |
| A | `src/test/java/com/tf/reader/hold/repository/HoldWritesIT.java` |

## `47dc64f` — 2026-08-24 — Ks-Gupta
**Implement CopyLease on Redis - sorted-set lease rows, atomic claim via Lua script**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/test/java/com/tf/reader/reading/service/CopyLeaseImplIT.java` |

## `b90c241` — 2026-08-24 — Ks-Gupta
**Add LoanProvisioning port and its stub - accept() has something real to call today**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/service/LoanProvisioning.java` |
| A | `src/main/java/com/tf/reader/hold/service/StubLoanProvisioning.java` |
| A | `src/test/java/com/tf/reader/hold/service/StubLoanProvisioningTest.java` |

## `276659d` — 2026-08-24 — Ks-Gupta
**Implement AvailabilityQueryImpl for real - CopyLease + Redis queue size, omit never zero**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/AvailabilityQueryImpl.java` |
| A | `src/test/java/com/tf/reader/hold/service/AvailabilityQueryImplTest.java` |

## `e86dc1c` — 2026-08-24 — Ks-Gupta
**Add AvailabilityController - GET /api/v1/items/{itemId}/availability, always 200**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/controller/AvailabilityController.java` |
| A | `src/test/java/com/tf/reader/hold/controller/AvailabilityControllerTest.java` |
| A | `src/test/java/com/tf/reader/hold/service/AvailabilityIT.java` |

## `bfd4d6e` — 2026-08-24 — Ks-Gupta
**Implement HoldSnapshotQueryImpl - thin adapter over QueueService.holdsFor() for library**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/HoldSnapshotQueryImpl.java` |
| A | `src/test/java/com/tf/reader/hold/api/AvailabilitySnapshotTest.java` |
| A | `src/test/java/com/tf/reader/hold/api/HoldSnapshotTest.java` |
| A | `src/test/java/com/tf/reader/hold/service/HoldSnapshotQueryImplTest.java` |

## `78285b8` — 2026-08-24 — Ks-Gupta
**Implement PromotionService for real - Redis lock, compare-and-delete release, real CopyLease**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/PromotionService.java` |
| A | `src/test/java/com/tf/reader/hold/service/PromotionServiceTest.java` |

## `a287b27` — 2026-08-24 — Ks-Gupta
**Add OfferSweeper - scheduled job expiring lapsed offers, never a Redis TTL**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |
| A | `src/test/java/com/tf/reader/hold/service/OfferSweeperIT.java` |

## `7267c56` — 2026-08-24 — Ks-Gupta
**Implement HoldPromotionImpl for real - fans out over every institution queuing the item**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/HoldPromotionImpl.java` |
| A | `src/test/java/com/tf/reader/hold/service/HoldPromotionImplTest.java` |

## `a41a0b8` — 2026-08-24 — Ks-Gupta
**Document OfferRepository as superseded - Offer is embedded in Hold, not its own collection**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/repository/OfferRepository.java` |

## `75eee8f` — 2026-08-24 — Ks-Gupta
**Fix OfferSweeper - give the sweep-interval placeholder a default, nothing defined it**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |

## `e9958e7` — 2026-08-24 — Ks-Gupta
**Fix HoldContainerTest - start containers once, not per test class**
Modules: `hold`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/hold/HoldContainerTest.java` |

## `3dae62b` — 2026-08-24 — Ks-Gupta
**Fix AvailabilityIT - seed a real copy-limited entitlement, self-healing cleanup**
Modules: `hold`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/hold/service/AvailabilityIT.java` |

## `2a2e5aa` — 2026-08-24 — hariii-1122
**Update library repository and assembler**
Modules: `library`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/library/repository/MockLibraryRepository.java` |
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `d596468` — 2026-08-24 — hariii-1122
**Merge pull request #19 from Deepu1004/reader_week_2**
Modules: `?`

| | File |
|---|---|

## `e7e513e` — 2026-08-24 — hemanthb1412
**SAML mock idp server handle with post calls for login**
Modules: `auth`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/auth/saml/mock/config/SamlMockComponent.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/config/SamlMockConfig.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/config/SamlMockProperties.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/controller/SamlMockController.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/model/SamlMockUser.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/security/SamlMockKeyService.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/service/SamlMockResponse.java` |
| A | `src/main/java/com/tf/reader/auth/saml/mock/service/SamlMockResponseBuilder.java` |

## `a9551ed` — 2026-08-24 — hariii-1122
**Update library assembler and remove mock repository**
Modules: `library`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/library/repository/MockLibraryRepository.java` |
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `7674093` — 2026-08-24 — Sai Deepak Varanasi
**Merge pull request #21 from Deepu1004/feature/hemanth-auth**
Modules: `?`

| | File |
|---|---|

## `71a1398` — 2026-08-24 — Ks-Gupta
**Revert CopyLeaseImpl to reading's own stub - not hold's file to implement**
Modules: `reading`

| | File |
|---|---|
| D | `src/test/java/com/tf/reader/reading/service/CopyLeaseImplIT.java` |

## `8bb0a56` — 2026-08-24 — Ks-Gupta
**Adjust AvailabilityIT for the CopyLease revert - drop the lease-math assertion**
Modules: `hold`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/hold/service/AvailabilityIT.java` |

## `8eefbb2` — 2026-08-24 — Ks-Gupta
**Revert CopyLeaseImpl content to reading's own stub**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |

## `7162696` — 2026-08-24 — KHUSHI GUPTA
**Merge pull request #22 from Deepu1004/khushi/hold**
Modules: `?`

| | File |
|---|---|

## `e6592d3` — 2026-08-24 — Ks-Gupta
**Fix HoldContainerTest - use ContainerisedInfrastructure, not a stale property name**
Modules: `hold`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/hold/HoldContainerTest.java` |

## `b012c1f` — 2026-08-24 — Sai Deepak Varanasi
**Lua Scripts for claim, release with atomic checks and expiry cleanup**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/main/java/com/tf/reader/reading/service/LeaseKeys.java` |
| A | `src/test/java/com/tf/reader/reading/service/CopyLeaseImplIT.java` |

## `d9c6b71` — 2026-08-24 — KHUSHI GUPTA
**Merge pull request #23 from Deepu1004/khushi/hold**
Modules: `?`

| | File |
|---|---|

## `a347a37` — 2026-08-24 — Ks-Gupta
**Restore the real lease-math assertion in AvailabilityIT**
Modules: `hold`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/hold/service/AvailabilityIT.java` |

## `6044a8f` — 2026-08-24 — Ks-Gupta
**Add QueueService.leave() - cancel a hold, promote the next reader if it was OFFERED**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| M | `src/test/java/com/tf/reader/hold/service/QueueServiceTest.java` |

## `9049146` — 2026-08-24 — Ks-Gupta
**Add HoldController.leave() - DELETE /api/v1/holds/{holdId}, always 204**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/controller/HoldController.java` |

## `1480dff` — 2026-08-24 — Ks-Gupta
**Un-defer QueueServiceIT - leave() exists now, seed a real entitlement for it**
Modules: `hold`

| | File |
|---|---|
| A | `src/test/java/com/tf/reader/hold/service/QueueServiceIT.java` |

## `78f5220` — 2026-08-24 — Sai Deepak Varanasi
**resolved test case issue 0 test issues now**
Modules: `auth`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/auth/saml/SamlSecurityEdgeCaseTest.java` |

## `e0eb643` — 2026-08-25 — Ks-Gupta
**Merge branch 'main' of https://github.com/Deepu1004/tf_reader_backend_temp into khushi/hold**
Modules: `?`

| | File |
|---|---|

## `abfa896` — 2026-08-25 — Sai Deepak Varanasi
**added reconciler with loan check only have to add to holds later**
Modules: `hold,library,loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/api/LiveOfferQuery.java` |
| A | `src/main/java/com/tf/reader/hold/api/LiveOfferView.java` |
| M | `src/main/java/com/tf/reader/hold/repository/HoldRepository.java` |
| A | `src/main/java/com/tf/reader/hold/service/LiveOfferQueryImpl.java` |
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanQuery.java` |
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanView.java` |
| M | `src/main/java/com/tf/reader/loan/repository/LoanRepository.java` |
| M | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| A | `src/main/java/com/tf/reader/reading/service/LeaseSeed.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `d6fb603` — 2026-08-25 — Shashank Kumar Lal
**CAP-4 DoD #2 + #4: integration tests (LoanLifecycleIT) and requests.http**
Modules: `loan`

| | File |
|---|---|
| A | `src/test/java/com/tf/reader/loan/LoanLifecycleIT.java` |

## `ebc2cb0` — 2026-08-25 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `bc38d9c` — 2026-08-25 — Ks-Gupta
**Fix stale database-mismatch note in MockHoldFixtures**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/repository/MockHoldFixtures.java` |

## `289b619` — 2026-08-25 — hemanthb1412
**removed mocked repository and added real-time sync with mongodb for universities**
Modules: `auth,hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/auth/authorization/AuthorizationService.java` |
| M | `src/main/java/com/tf/reader/auth/controller/AuthController.java` |
| M | `src/main/java/com/tf/reader/auth/oidc/client/OidcAuthenticationService.java` |
| D | `src/main/java/com/tf/reader/auth/repository/MockInstitutionRepository.java` |
| M | `src/main/java/com/tf/reader/auth/repository/MockUserRepository.java` |
| M | `src/main/java/com/tf/reader/auth/saml/SamlAuthenticationService.java` |
| M | `src/main/java/com/tf/reader/hold/repository/MockHoldFixtures.java` |
| A | `src/test/java/com/tf/reader/auth/AuthTestInstitutions.java` |
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/controller/AuthControllerTest.java` |
| M | `src/test/java/com/tf/reader/auth/controller/AuthMeTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/EndToEndAuthFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/e2e/OidcEndToEndAuthFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/oidc/client/OidcTransactionStoreTest.java` |
| M | `src/test/java/com/tf/reader/auth/oidc/client/OidcUserMapperTest.java` |
| M | `src/test/java/com/tf/reader/auth/oidc/validation/OidcIdTokenValidationTest.java` |
| M | `src/test/java/com/tf/reader/auth/repository/MockUserRepositoryTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandlerTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlLoginFlowTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlSecurityEdgeCaseTest.java` |
| M | `src/test/java/com/tf/reader/auth/saml/SamlUserMapperTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/CurrentUserJwtConverterTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/JwtAuthenticationTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/SensitiveDataLoggingTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/StatelessApiTest.java` |
| M | `src/test/java/com/tf/reader/auth/security/TnfJwtValidatorTest.java` |
| M | `src/test/java/com/tf/reader/auth/token/JwtTokenServiceTest.java` |
| M | `src/test/java/com/tf/reader/auth/transaction/AuthTransactionStoreTest.java` |

## `58250f0` — 2026-08-25 — KHUSHI GUPTA
**Merge pull request #28 from Deepu1004/khushi/hold**
Modules: `?`

| | File |
|---|---|

## `bacff72` — 2026-08-25 — Sai Deepak Varanasi
**Merge pull request #27 from Deepu1004/feature/hemanth-auth**
Modules: `?`

| | File |
|---|---|

## `61ed069` — 2026-08-25 — Shashank Kumar Lal
**CAP-4 D-026: add borrowedAt and status to ActiveLoanView**
Modules: `library,loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| M | `src/main/java/com/tf/reader/loan/api/ActiveLoanView.java` |
| M | `src/main/java/com/tf/reader/loan/service/ActiveLoanQueryImpl.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `0ef42ac` — 2026-08-25 — Ks-Gupta
**Delete LoanProvisioning and its stub - loan.api.LicenceCommand is published now**
Modules: `hold`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/hold/service/LoanProvisioning.java` |
| D | `src/main/java/com/tf/reader/hold/service/StubLoanProvisioning.java` |
| D | `src/test/java/com/tf/reader/hold/service/StubLoanProvisioningTest.java` |

## `203ac30` — 2026-08-25 — Ks-Gupta
**Add QueueService.accept() - turn a live offer into a loan via LicenceCommand**
Modules: `hold`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/dto/AcceptedLoanResponse.java` |
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| M | `src/test/java/com/tf/reader/hold/service/QueueServiceTest.java` |

## `9198713` — 2026-08-25 — Ks-Gupta
**Add HoldController.accept() - POST /api/v1/holds/{holdId}/accept, 201**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/controller/HoldController.java` |

## `051eef6` — 2026-08-25 — Ks-Gupta
**Un-defer PromotionIT - accept() exists now, seed a real entitlement for it**
Modules: `hold`

| | File |
|---|---|
| A | `src/test/java/com/tf/reader/hold/service/PromotionIT.java` |

## `bc3a732` — 2026-08-25 — Shashank Kumar Lal
**Fix BorrowResponse to include userId and institutionId per contract Loan schema**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/dto/BorrowResponse.java` |
| M | `src/main/java/com/tf/reader/loan/service/BorrowService.java` |

## `f275e3a` — 2026-08-25 — Ks-Gupta
**Merge branch 'main' of https://github.com/Deepu1004/tf_reader_backend_temp into khushi/hold**
Modules: `?`

| | File |
|---|---|

## `2be4e7d` — 2026-08-25 — Ks-Gupta
**Align AcceptedLoanResponse with BorrowResponse's userId/institutionId fix**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/dto/AcceptedLoanResponse.java` |
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| M | `src/test/java/com/tf/reader/hold/service/QueueServiceTest.java` |

## `0f8259a` — 2026-08-25 — hariii-1122
**fix(library): guard the shelf against non-live loans from the seam**
Modules: `library`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/library/service/LibraryAssembler.java` |
| M | `src/test/java/com/tf/reader/library/LibraryAssemblerTest.java` |

## `075c6cc` — 2026-08-25 — Shashank Kumar Lal
**CAP-4 D-027: IdempotencyStore on borrow and return endpoints**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| A | `src/main/java/com/tf/reader/loan/service/IdempotencyStore.java` |
| A | `src/test/java/com/tf/reader/loan/IdempotencyStoreTest.java` |

## `b5fe59b` — 2026-08-25 — hariii-1122
**Merge pull request #33 from Deepu1004/reader_week_2**
Modules: `?`

| | File |
|---|---|

## `760b9e8` — 2026-08-25 — abhishek-tf
**added public open access catalogue feed**
Modules: `auth`

| | File |
|---|---|
| M | `src/test/java/com/tf/reader/auth/authorization/AuthorizationCoverageTest.java` |

## `a2a1e51` — 2026-08-25 — Abhinav
**Refactor RightsService and its tests to clarify audio download rules**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/RightsService.java` |
| M | `src/test/java/com/tf/reader/reading/service/RightsServiceTest.java` |

## `225230e` — 2026-08-25 — Abhinav-TF
**Merge PR #34 from Abhinav**
Modules: `?`

| | File |
|---|---|

## `f2e2f7c` — 2026-08-25 — Abhinav
**Update ReadBrokerService to extend claim to session lifetime instead of loan due date**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/test/java/com/tf/reader/reading/service/ReadBrokerServiceTest.java` |

## `22b8a40` — 2026-08-25 — Abhinav-TF
**Merge pull request #35 from Deepu1004/Abhinav**
Modules: `?`

| | File |
|---|---|

## `e2331b8` — 2026-08-25 — Sai Deepak Varanasi
**Merge branch 'abhishek-tf:main' into main**
Modules: `?`

| | File |
|---|---|

## `f76cb4b` — 2026-08-25 — Sai Deepak Varanasi
**finalised the edge cases for reconciler**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |

## `edbd44b` — 2026-08-25 — Sai Deepak Varanasi
**integration test files for reconciler with both redis queue and loans to test state after a restart**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/CopyLeaseImpl.java` |
| M | `src/main/java/com/tf/reader/reading/service/LeaseKeys.java` |
| M | `src/main/java/com/tf/reader/reading/service/ReconcilerService.java` |
| A | `src/test/java/com/tf/reader/reading/service/ReconcilerServiceIT.java` |

## `eee9a03` — 2026-08-26 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `bb562f4` — 2026-08-26 — Shashank Kumar Lal
**CAP-4 D-028: LoanSeedRunner seeds demo loans on an empty local DB (DoD #6)**
Modules: `loan`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/loan/service/LoanSeedRunner.java` |
| A | `src/test/java/com/tf/reader/loan/LoanSeedRunnerTest.java` |

## `649b3e1` — 2026-08-26 — Sai Deepak Varanasi
**Dev seed data — loan/hold/reading/library fixtures**
Modules: `hold,library,loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/service/HoldDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/library/service/ChangeLogDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/loan/service/LoanDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/reading/service/DeviceDevDataSeeder.java` |

## `cd46c22` — 2026-08-26 — SHASHANK KUMAR LAL
**Merge pull request #37 from Deepu1004/feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `90012ef` — 2026-08-26 — Shashank Kumar Lal
**Remove LoanSeedRunner — superseded by team's coordinated flambeau-seed (D-028)**
Modules: `loan`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/loan/service/LoanSeedRunner.java` |
| D | `src/test/java/com/tf/reader/loan/LoanSeedRunnerTest.java` |

## `7744bac` — 2026-08-26 — Abhinav
**Enhance device cap checks for Elite access and add tests for subscription and open access bypass**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/test/java/com/tf/reader/reading/service/ReadBrokerServiceTest.java` |

## `79cfdaf` — 2026-08-26 — Abhinav
**Merge branch 'main' of https://github.com/Deepu1004/tf_reader_backend_temp into Abhinav**
Modules: `?`

| | File |
|---|---|

## `7cbb29d` — 2026-08-26 — Shashank Kumar Lal
**Merge remote-tracking branch 'origin/main' into feature/shashank-loan**
Modules: `?`

| | File |
|---|---|

## `6f8d2c7` — 2026-08-26 — Sai Deepak Varanasi
**Dev seed data — loan/hold/reading/library fixtures**
Modules: `hold,library,loan,reading`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/hold/service/HoldDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/library/service/ChangeLogDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/loan/service/LoanDevDataSeeder.java` |
| A | `src/main/java/com/tf/reader/reading/service/DeviceDevDataSeeder.java` |

## `0ce135d` — 2026-08-25 — Shashank Kumar Lal
**CAP-4 D-027: IdempotencyStore on borrow and return endpoints**
Modules: `loan`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/loan/controller/LoanController.java` |
| A | `src/main/java/com/tf/reader/loan/service/IdempotencyStore.java` |
| A | `src/test/java/com/tf/reader/loan/IdempotencyStoreTest.java` |

## `2a442c2` — 2026-08-26 — Shashank Kumar Lal
**CAP-4 D-028: LoanSeedRunner seeds demo loans on an empty local DB (DoD #6)**
Modules: `loan`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/loan/service/LoanSeedRunner.java` |
| A | `src/test/java/com/tf/reader/loan/LoanSeedRunnerTest.java` |

## `9c977eb` — 2026-08-26 — Shashank Kumar Lal
**Remove LoanSeedRunner — superseded by team's coordinated flambeau-seed (D-028)**
Modules: `loan`

| | File |
|---|---|
| D | `src/main/java/com/tf/reader/loan/service/LoanSeedRunner.java` |
| D | `src/test/java/com/tf/reader/loan/LoanSeedRunnerTest.java` |

## `fe31710` — 2026-08-26 — Abhinav
**Enhance device cap checks for Elite access and add tests for subscription and open access bypass**
Modules: `reading`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/reading/service/ReadBrokerService.java` |
| M | `src/test/java/com/tf/reader/reading/service/ReadBrokerServiceTest.java` |

## `d609bf6` — 2026-08-26 — hariii-1122
**Add library endpoint integration test**
Modules: `library`

| | File |
|---|---|
| A | `src/test/java/com/tf/reader/library/LibraryEndpointsIT.java` |

## `8e548cd` — 2026-08-26 — hariii-1122
**Merge pull request #40 from Deepu1004/reader_week_2**
Modules: `?`

| | File |
|---|---|

## `473125e` — 2026-08-26 — Ks-Gupta
**Wire hold's change-log events to the real ChangeLog port**
Modules: `hold`

| | File |
|---|---|
| M | `src/main/java/com/tf/reader/hold/service/OfferSweeper.java` |
| M | `src/main/java/com/tf/reader/hold/service/PromotionService.java` |
| M | `src/main/java/com/tf/reader/hold/service/QueueService.java` |
| M | `src/test/java/com/tf/reader/hold/service/PromotionIT.java` |
| M | `src/test/java/com/tf/reader/hold/service/PromotionServiceTest.java` |
| M | `src/test/java/com/tf/reader/hold/service/QueueServiceIT.java` |
| M | `src/test/java/com/tf/reader/hold/service/QueueServiceTest.java` |

## `1806bf9` — 2026-08-26 — KHUSHI GUPTA
**Merge pull request #41 from Deepu1004/khushi/hold**
Modules: `?`

| | File |
|---|---|

## `613ba45` — 2026-08-27 — Sai Deepak Varanasi
**added outbox for log writers to ensure reconciler**
Modules: `library`

| | File |
|---|---|
| A | `src/main/java/com/tf/reader/library/entity/OutboxEntry.java` |
| A | `src/main/java/com/tf/reader/library/repository/ChangeLogOutboxRepository.java` |
| M | `src/main/java/com/tf/reader/library/service/ChangeLogWriter.java` |
| M | `src/main/java/com/tf/reader/library/service/OutboxReplayService.java` |
| M | `src/test/java/com/tf/reader/library/ChangeLogWriterTest.java` |
| A | `src/test/java/com/tf/reader/library/OutboxReplayServiceIT.java` |

## `e1d9757` — 2026-08-27 — Sai Deepak Varanasi
**Merge pull request #43 from Deepu1004/read-access-and-concurrency**
Modules: `?`

| | File |
|---|---|

---

## Regenerating this file

```bash
git log --reverse --pretty=format:'§§%h|%ad|%an|%s' --date=short --name-status -- \
  src/main/java/com/tf/reader/auth/ src/test/java/com/tf/reader/auth/ \
  src/main/java/com/tf/reader/loan/ src/test/java/com/tf/reader/loan/ \
  src/main/java/com/tf/reader/hold/ src/test/java/com/tf/reader/hold/ \
  src/main/java/com/tf/reader/reading/ src/test/java/com/tf/reader/reading/ \
  src/main/java/com/tf/reader/library/ src/test/java/com/tf/reader/library/
```

Then parse on the `§§` marker: each block is `hash|date|author|subject` followed by
tab-separated `status<TAB>path` lines. Only regenerate the *history* section this way — if
you've been appending new entries manually per the convention above, diff before overwriting
so a manually-added recent entry isn't lost.
