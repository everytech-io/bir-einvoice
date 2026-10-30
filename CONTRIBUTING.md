# Contributing to bir-einvoice

Thank you for your interest. bir-einvoice is a Kotlin Multiplatform client library for the
Philippine BIR's Electronic Invoicing System (EIS) API. This guide is for developers who want to
fix a bug, add a test, improve the documentation or follow a change in the BIR's specifications.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Scope and non-goals

In scope:

* The three EIS calls a seller's system makes: authentication, invoice issuance and result
  inquiry.
* The CAS e-invoice JSON model, with the BIR's field names and order.
* A validator that checks a payload the way EIS does.
* The transport crypto from BIR's API guide (RSA, AES, HMAC, JWS).
* `bir-einvoice-mock`, a local stand-in for EIS for tests.

Not in scope:

* User interfaces, invoice storage, ledgers, printing or business workflows. Those belong in the
  application that uses the library. See EveryInvoice,
  [everytech-io/everyinvoice](https://github.com/everytech-io/everyinvoice), for one.
* Anything that talks to the live BIR sandbox or production from the test suite or CI. Tests use
  the mock and made-up values.
* Tax or legal advice, or a claim that a payload will be accepted. The BIR's sandbox has the
  final word.
* Extra runtime dependencies. The library depends on the Kotlin standard library and
  kotlinx-serialization-json only. Open an issue first if you think another one is needed.
* Behaviour that differs from the BIR's documents because it seems nicer. If the BIR's documents
  look wrong, say so in an issue and cite them. Do not guess.

If you are unsure whether an idea fits, open an issue before you write code.

## Reporting bugs and security problems

* A bug: open an issue with the
  [bug report form](https://github.com/everytech-io/bir-einvoice/issues/new?template=bug_report.yml).
  A snippet that fails against `MockEisServer` is the quickest to fix.
* A security problem: do not open an issue. Follow [SECURITY.md](SECURITY.md).
* A question: see [SUPPORT.md](SUPPORT.md).

Issues and pull requests are public. Never include real TINs, invoices, EIS credentials,
application keys, signing keys or tokens. Use made-up values.

## Set up your machine

You need a JDK 21. The Gradle wrapper downloads Gradle, Kotlin and, for the Wasm tests, Node.js.
You do not need to install Gradle, Kotlin or Node.

macOS:

```bash
brew install --cask temurin@21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

Linux (Debian, Ubuntu):

```bash
sudo apt install openjdk-21-jdk     # or install Temurin 21 from https://adoptium.net
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

Windows (PowerShell):

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
# open a new terminal so JAVA_HOME and PATH are updated
```

Then:

```bash
git clone https://github.com/everytech-io/bir-einvoice.git
cd bir-einvoice
./gradlew check          # on Windows: .\gradlew.bat check
```

If you work on a fork, clone your fork and add this repository as `upstream`.

## Build and test

| Command | What it does |
|---|---|
| `./gradlew check` | All tests: JVM (JUnit, including the Java test) and Kotlin/Wasm on Node.js |
| `./gradlew :bir-einvoice:jvmTest` | JVM tests of the library only |
| `./gradlew :bir-einvoice-mock:run` | Start the standalone mock EIS |
| `./gradlew publishToMavenLocal` | Install `io.everytech:bir-einvoice` and `bir-einvoice-mock` locally |

Two optional interop tests compare this library with an independent implementation of the same
protocol, which is not published. They are skipped unless their variables are set (see "Building"
in the [README](README.md)). You do not need them to contribute; CI does not run them.

## Code style

* Kotlin official style (`kotlin.code.style=official`). [.editorconfig](.editorconfig) sets
  indentation, line endings and a 120 column limit. No formatter is enforced yet. Match the code
  around your change.
* Put code in `commonMain` unless it needs the JDK. `EisCrypto`, `EisClient` and the JDK
  transport are JVM only on purpose: a browser cannot call EIS and must not hold a signing key.
* Money is `Amount`, integer hundredths. Do not use `Double` or `Float` for amounts.
* Public API gets KDoc. Java callers matter: avoid Kotlin-only features (value classes, default
  arguments without `@JvmOverloads`) on public API.
* Never log or print a secret. `EisCredentials.toString()` must stay free of secrets.
* Comments say why, not what. Plain, short sentences. No emojis.

## Tests

Every change in behaviour comes with a test. Bug fixes start with a test that fails.

* Put tests that do not need the JDK in `commonTest`; they run on the JVM and on Wasm.
* Crypto changes need test vectors that were checked against an independent implementation, such
  as OpenSSL, not only a round trip through this library.
* Validator and mock changes need a case for the accepted and the rejected payload, using the
  EIS result code the BIR documents (for example `SYN004`, `ERR004`).
* Tests must not use the network, except for loopback to `MockEisServer`.

## BIR specifications: cite the source

This library is only useful if it matches what the BIR publishes. So:

* **Every rule must cite its official BIR source.** Put the document, its version, and the
  section, table or page in a comment next to the rule, in the test that covers it, and in the
  pull request. Example: `EIS e-invoice API Development Guide v2.3, section 4.2`.
* Official sources are the documents on the
  [EIS Certification Portal](https://eis-cert.bir.gov.ph/#/downloads) and BIR issuances such as
  RMC No. 98-2026. A forum post, a blog, another vendor's library or a reply from a support agent
  is not a source. It can be a lead; confirm it in a BIR document or in the sandbox, and say
  which.
* If a rule comes from observed sandbox behaviour and not from a document, say so, with the date
  and the response you saw (without credentials).
* If two BIR documents disagree, or a document is silent, do not pick one quietly. Open an issue,
  quote both, and wait for a maintainer.
* Do not add BIR documents to the repository. Link to them. `docs/EIS-API-SPEC.md` and
  `docs/eis-api.openapi.yaml` are our own digest; when they disagree with a BIR document, the BIR
  document wins and the digest gets fixed.

### How specification changes are tracked

When the BIR publishes a new version of a guide or a circular:

1. Anyone opens an issue with the `bir-spec` label. Name the document, the old and the new
   version, and link it.
2. The issue lists each changed rule, with the BIR section, and the code, tests and docs it
   affects.
3. Each change goes in as a pull request that cites that section.
4. The [CHANGELOG](CHANGELOG.md) records it under "Changed" with the note "BIR spec" and the
   document version. The README states which versions the library follows.

## Commit messages: Conventional Commits

Use [Conventional Commits](https://www.conventionalcommits.org): `type(scope): summary`.

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`. Scopes are
optional; useful ones are `crypto`, `client`, `validator`, `model`, `mock` and `wasm`. Write the
summary in the imperative, in lower case, without a final period. Mark a breaking change with `!`
(`feat(model)!: ...`) and a `BREAKING CHANGE:` paragraph.

```
fix(validator): reject VAT on zero-rated sales

ERR004 requires VAT to be 0 on zero-rated and exempt sales.
Source: EIS e-invoice API Development Guide v2.3, error table.

Signed-off-by: Your Name <you@example.com>
```

Keep commits small and logical. We squash or rebase on merge, so the commit messages on `main`
read as a changelog.

## Sign off your commits (Developer Certificate of Origin)

This project uses the [Developer Certificate of Origin](https://developercertificate.org) (DCO)
instead of a contributor license agreement. The DCO is a short statement, the same one the Linux
kernel uses, in which you certify that you wrote the change or have the right to submit it under
the project's license (Apache-2.0). You keep your copyright.

Certify it by adding a `Signed-off-by` line to every commit:

```bash
git commit -s -m "fix(validator): reject VAT on zero-rated sales"
```

That adds `Signed-off-by: Your Name <you@example.com>`, using your `user.name` and `user.email`.
Use your real name. To fix commits you already made:

```bash
git commit --amend -s --no-edit          # the last commit
git rebase --signoff origin/main         # every commit on your branch
git push --force-with-lease
```

A check named `dco` runs on every pull request and fails if a commit has no sign-off.

## Pull requests and review

1. Open or find an issue first for anything larger than a typo or a small fix.
2. Fork, then branch from `main`: `git switch -c fix/zero-rated-vat`.
3. Make the change, with tests and docs. Run `./gradlew check`.
4. Open a pull request and fill in the template. Keep it to one topic.
5. CI must pass: the `check` job and the `dco` job.
6. A maintainer reviews it. We aim to give a first reply within 7 days. Reviews are about
   correctness against the BIR sources, tests, API shape and clarity. Expect questions; they are
   not criticism.
7. At least one maintainer approval is required, and a code owner for `bir-einvoice/`. Changes to
   crypto, credentials handling or the public API need a second look and may take longer.
8. Address comments with new commits. We squash when merging if the history is noisy.

Do not force-push over a review in progress without saying so. Draft pull requests are welcome.

## Good first issues

Issues labelled `good first issue` are small, self-contained and described well enough to start.
Typical ones:

* a missing test for a documented EIS error or result code;
* a clarification in the README or in `docs/`;
* a Java usage example;
* checking a rule in the validator against the BIR guide and citing the section.

Comment on the issue to claim it. If you have not made progress for two weeks, we may offer it to
someone else.

## Translations and i18n

The library has no user-facing text beyond error messages, which are in English, and the error
code tables, which follow the BIR's wording. Translations of the documentation are welcome later.
Open an issue first so we can agree where translated files live. The English text is the
reference.

## Releases and versioning

* [Semantic Versioning](https://semver.org). While the version is `0.x`, a minor release may
  contain breaking changes; they are always listed in the [CHANGELOG](CHANGELOG.md). From `1.0.0`,
  breaking changes only come in major releases.
* Releases are git tags of the form `vMAJOR.MINOR.PATCH`. JitPack builds a tag on demand.
* Maintainers cut releases. Contributors do not bump versions in pull requests.
* The changelog follows [Keep a Changelog](https://keepachangelog.com). Add a line under
  `Unreleased` for any change a user would notice.
* A change that follows a new BIR specification version may be released as a minor version even
  if it changes behaviour, because the BIR's rules are the contract. It is listed as a change
  with the BIR document version.

## Licensing

By contributing you license your work under the [Apache License 2.0](LICENSE). Do not copy code
you are not allowed to relicense, and do not paste text from BIR documents or sample code beyond
a short quotation that you cite. See [TRADEMARKS.md](TRADEMARKS.md) for the use of names and logos.
