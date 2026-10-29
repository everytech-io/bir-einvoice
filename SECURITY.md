# Security policy

## Report a vulnerability privately

Never report a security problem in a public issue, discussion or pull request. Use GitHub's
private vulnerability reporting instead:

1. Open the [Security tab](https://github.com/everytech-io/bir-einvoice/security) of this
   repository.
2. Click **Report a vulnerability** (or go straight to
   [the private report form](https://github.com/everytech-io/bir-einvoice/security/advisories/new)).
3. Describe the problem, the library version, your Kotlin or Java version, and a minimal way to
   show it, ideally against `MockEisServer`.

Only you and the maintainers can see the report. We answer in the same private thread, work on a
fix there, and publish an advisory with the release that fixes it. You are credited unless you
ask not to be.

Use made-up values. Do not send real TINs, invoices, EIS credentials, application keys, signing
keys or tokens, even privately.

## Supported versions

Fixes go into the latest version on `main` and the next tagged release. Please check that the
problem still happens there.

## In scope

* `bir-einvoice`: `EisClient`, `EisCrypto` (RSA, AES, HMAC and JWS as BIR specifies them),
  `EisCredentials` (including anything that prints or logs a secret), `EisJson`, `CasValidator`
  and `EisIds`.
* `bir-einvoice-mock`, where a flaw could make a client look correct when it is not.
* The build and the CI workflow in `.github/workflows/`.

## Out of scope

* Weaknesses BIR's protocol itself requires, such as the AES IV derived from the key (see the
  README). Report those to the BIR.
* The BIR's own systems (EIS, the EIS Certification Portal).
* The EveryTech e-Invoice desktop app: report those at
  [everytech-io/einvoice](https://github.com/everytech-io/einvoice/security).
* How an application that uses this library stores its credentials or keys.
