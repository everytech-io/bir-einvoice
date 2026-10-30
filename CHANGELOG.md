# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is 0.x, a minor
release may contain breaking changes.

Changes that follow a new BIR specification are marked "BIR spec" and name the document version.

## [Unreleased]

Version 0.1.0 is in preparation and has not been tagged.

### Added

- Kotlin Multiplatform client for the BIR EIS e-invoice API: the CAS sheet v2.2 invoice model with
  the BIR's field names and order, `Amount` as integer hundredths, a validator that reads raw
  number text, EIS request-error and result-code tables, and ID formats. (BIR spec: API
  Development Guide v2.3, JSON File Format v2.1)
- The transport crypto from the guide: AES/CBC/PKCS5 with the IV derived from the key, RSA
  PKCS#1 v1.5, HMAC-SHA256 and RS256 JWS.
- A blocking, Java-friendly `EisClient` on the JDK `HttpClient`, with sandbox pacing and one
  re-authentication on E03.
- `bir-einvoice-mock`: `MockEisServer` for tests, a standalone runner, and `MockEis`, the same
  processing without the HTTP and crypto layers.
- A Kotlin/Wasm (`wasmJs`) target for the model, validator, codes, IDs and `MockEis`, so they run
  in a browser. `EisIds` uses `crypto.getRandomValues` there.
- Documentation: README, a digest of the EIS API specification and an OpenAPI description.
- Issue forms, a security policy, and a JitPack build configuration.
- Open-source project files: contributing guide, code of conduct, governance, support, trademark
  policy, NOTICE, code owners, pull request template, `.editorconfig`, Dependabot configuration
  and a DCO check.

### Changed

- `Amount` is a plain data class, so Java can construct `CasInvoice`.
- `bir-einvoice-mock` is a Kotlin Multiplatform module (JVM and Wasm).

[Unreleased]: https://github.com/everytech-io/bir-einvoice/commits/main
