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

If you cannot use GitHub, write to **contact@everytech.io** with the subject "Security:
bir-einvoice". Ask for a private channel before you send details.

Only you and the maintainers can see a report. We answer in the same private thread, work on a
fix there, and publish an advisory with the release that fixes it. You are credited unless you
ask not to be.

Use made-up values. Do not send real TINs, invoices, EIS credentials, application keys, signing
keys or tokens, even privately.

## What to expect

| Step | Target |
|---|---|
| We acknowledge your report | within 3 business days |
| We confirm the problem or ask for more detail | within 7 days |
| We share a plan, or a fix for you to test | within 30 days for a confirmed problem |
| We publish the fix and an advisory | within 90 days of the report, sooner when we can |

These are targets from a small team, not guarantees. If a fix needs longer, for example because
it depends on the BIR, we say so in the thread and agree a date with you. We ask you not to
disclose the problem publicly until the fix is released or the 90 days have passed, whichever
comes first. We will not take legal action against anyone who reports in good faith, follows this
policy, and does not access other people's data or disrupt the BIR's systems.

There is no bug bounty.

## Supported versions

The library is at 0.x. Fixes go into the latest version on `main` and the next tagged release.
Older releases do not get backports. Please check that the problem still happens on `main`.

## What this library does and does not do

The library has no server and no telemetry. It sends requests only to the `baseUrl` the calling
application gives it (the BIR's EIS by default) and keeps nothing on disk. EveryTech never
receives your invoices, credentials or keys through this library.

## In scope

* `bir-einvoice`: `EisClient`, `EisCrypto` (RSA, AES, HMAC and JWS as BIR specifies them),
  `EisCredentials` (including anything that prints or logs a secret), `EisJson`, `CasValidator`
  and `EisIds`.
* `bir-einvoice-mock`, where a flaw could make a client look correct when it is not.
* The build and the CI workflows in `.github/workflows/`, including the supply chain of the
  published artifacts.

## Out of scope

* Weaknesses BIR's protocol itself requires, such as the AES IV derived from the key (see the
  README). Report those to the BIR.
* The BIR's own systems (EIS, the EIS Certification Portal). Report those to the BIR.
* The EveryTech e-Invoice app: report those at
  [everytech-io/einvoice](https://github.com/everytech-io/einvoice/security).
* How an application that uses this library stores its credentials or keys.
* Denial of service by flooding the mock, which is a test tool and not meant to face a network.
* Reports from automated scanners without a demonstrated impact.
