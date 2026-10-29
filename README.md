# bir-einvoice

A Kotlin Multiplatform client library for the e-invoice API of the Philippine Bureau of Internal
Revenue's Electronic Invoicing System (EIS). It covers the three calls a seller's system makes
(authentication, invoice issuance, result inquiry), the CAS e-invoice JSON model, a validator
that checks a payload the way EIS does, and the transport crypto from BIR's API guide.

It also ships `bir-einvoice-mock`, a local stand-in for EIS, so you can test the whole flow
before you have sandbox credentials.

> **Not affiliated with or endorsed by the BIR.** This is independent open-source software by
> EveryTech. It is not accredited, certified, or endorsed by the Bureau of Internal Revenue.
> The BIR's own documents are the authority. A payload that passes the mock or the validator
> here can still be rejected by the BIR sandbox, and the sandbox has the final word.

Status: 0.1.0. JVM target (JDK 21). The source sets are laid out so Android and iOS targets can
be added later. The crypto follows BIR's Java, Python and C# samples. The client and the mock
have been tested in both directions against a separate Node.js implementation of the same
protocol. Nobody has run this library against the live BIR sandbox yet.

## What is in it

| Module | Coordinates | Contents |
|---|---|---|
| `bir-einvoice` | `io.everytech:bir-einvoice:0.1.0` | `CasInvoice` model, `Amount`, `EisJson`, `CasValidator`, `EisCodes`, `EisCrypto`, `EisIds`, `EisClient` |
| `bir-einvoice-mock` | `io.everytech:bir-einvoice-mock:0.1.0` | `MockEisServer` for tests, plus a standalone runner |

Dependencies: the Kotlin standard library and kotlinx-serialization-json. HTTP uses the JDK's
`java.net.http.HttpClient`; the mock uses the JDK's built-in `com.sun.net.httpserver`.

## Install

### Gradle composite build (works today)

Clone this repository next to your project and include it. Gradle swaps the coordinates for the
source build.

```kotlin
// settings.gradle.kts
includeBuild("../bir-einvoice")

// build.gradle.kts
dependencies {
    implementation("io.everytech:bir-einvoice:0.1.0")
    testImplementation("io.everytech:bir-einvoice-mock:0.1.0")
}
```

Or run `./gradlew publishToMavenLocal` here and add `mavenLocal()` to your repositories.

### JitPack (once a release is tagged)

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.everytech-io:bir-einvoice:<tag>")
}
```

JitPack serves the individual modules as `com.github.everytech-io.bir-einvoice:bir-einvoice:<tag>`
and `com.github.everytech-io.bir-einvoice:bir-einvoice-mock:<tag>`.

## Credentials

Everything comes from the [EIS Certification Portal](https://eis-cert.bir.gov.ph), under the
account of the taxpayer who issues the invoices.

| Field | Where it comes from |
|---|---|
| `eisCertNumber` | The EIS Cert Number (formerly Accreditation ID) on the application's EIS Cert page. It is sent as the `accreditationId` header. |
| `applicationId`, `applicationKey` | The application's Setting page. The key signs the authentication call only. |
| `userId`, `password` | Your EIS Certification Portal login. |
| `eisPublicKey` | The EIS Certification Public Key from the portal's Downloads page, as base64 SPKI or PEM. |
| `signingKeyId`, `signingPrivateKey` | Your own RSA signing key pair and its key ID. The private key is PKCS#8, base64 DER or PEM. The key ID goes in each invoice's JWS `kid`. |
| `baseUrl` | Defaults to the sandbox, `https://eis-cert.bir.gov.ph`. Take the production URL from the portal once your PTT is issued. |

Keep these out of source control. `EisCredentials.toString()` never prints a secret, and
`EisCredentials.toJson()` / `fromJson()` read and write a plain JSON file you can store with
owner-only permissions.

The sandbox allows one call per 10 seconds and answers faster callers with error E19. When the
base URL is the sandbox, the client waits 10.5 seconds between calls. For any other base URL it
does not wait. Pass `sandboxSpacingMillis` to change either.

## Usage (Kotlin)

```kotlin
import io.everytech.bireinvoice.*
import java.io.File

val credentials = EisCredentials.fromJson(File("eis-credentials.json").readText())
val client = EisClient(credentials)

val issueDate = "2026-10-29"
val invoice = CasInvoice(
    compInvoiceId = "INV-00000001",
    issueDtm = "20261029",
    eisUniqueId = EisIds.eisUniqueId(issueDate, credentials.eisCertNumber),
    docType = EisCodes.DOC_INVOICE,
    transClass = EisCodes.TRANS_VATABLE,
    corrYN = "N",
    sellerInfo = SellerInfo(
        tin = "123456789", branchCd = "00000", type = EisCodes.SELLER_VAT,
        regNm = "SAMPLE TRADING CORP.", regAddr = "1 Ayala Ave, Makati City",
    ),
    buyerInfo = BuyerInfo(tin = "987654321", branchCd = "00000", regNm = "BUYER INC."),
    itemList = listOf(
        CasItem(
            nm = "Widget", desc = "Blue", qty = Amount.parse("3"), unit = "pc",
            unitCost = Amount.parse("1000.00"), salesAmt = Amount.parse("3000.00"),
            regDscntAmt = Amount.ZERO, speDscntAmt = Amount.ZERO, netSales = Amount.parse("3000.00"),
        ),
    ),
    totNetItemSales = Amount.parse("3000.00"),
    discount = Discount(Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO),
    otherTaxRev = Amount.ZERO,
    totNetSalesAftDisct = Amount.parse("3000.00"),
    vatAmt = Amount.parse("360.00"),
    withholdIncome = Amount.ZERO,
    withholdBusVat = Amount.ZERO,
    withholdBusPt = Amount.ZERO,
    otherNonTaxCharge = Amount.ZERO,
    netAmtPay = Amount.parse("3360.00"),
    ptuNum = "AC-0001",
)

val json = EisJson.encode(invoice)          // spec field order, amounts like 3360.00
val problems = CasValidator.validate(json)  // [] when it passes
require(problems.isEmpty()) { problems.joinToString("\n") }

val ack = client.submit(listOf(json))       // signs, encrypts, sends; 1 to 100 per call
var result = client.inquire(ack.submitId)
while (result.processStatusCode == EisCodes.PROCESS_IN_PROGRESS) {
    Thread.sleep(60_000)
    result = client.inquire(ack.submitId)
}
result.processedDocuments.forEach { println("${it.invoiceUid}: ${it.resultStatusCode} ${it.description ?: ""}") }
```

Calls block. Run them off the UI thread, for example in `withContext(Dispatchers.IO)`.

Money is never a floating-point number here. `Amount` holds integer hundredths and writes exactly
two decimals. A credit memo uses `DocType` `03`, `CorrYN` `Y`, a correction code from
`EisCodes.correctionCodes`, the corrected invoice's `EisUniqueId` in `PrevUniqueId` (or
`EisCodes.UNMANAGED_ORIGINAL` when the original was never in EIS), and negative amounts.

## Usage (Java)

The client, crypto, IDs and validator work on JSON text and are plain Java calls. `EisException`
is a checked exception.

```java
import io.everytech.bireinvoice.*;
import java.nio.file.*;
import java.util.List;

EisCredentials credentials = EisCredentials.fromJson(Files.readString(Path.of("eis-credentials.json")));
EisClient client = new EisClient(credentials);

String json = Files.readString(Path.of("invoice.json"));
List<SchemaProblem> problems = CasValidator.validate(json);
if (!problems.isEmpty()) throw new IllegalStateException(problems.toString());

try {
    SubmitAck ack = client.submit(List.of(json));
    InquiryResult result = client.inquire(ack.getSubmitId());
    System.out.println(result.getProcessStatusCode());
} catch (EisException e) {
    System.err.println(e.getCode() + " " + EisCodes.getRequestErrors().get(e.getCode()));
}
```

Java can also build the typed model and let the library write the JSON:

```java
Amount zero = new Amount(0L);                       // integer hundredths: new Amount(85000L) is 850.00
CasItem item = new CasItem("Widget", "Blue", new Amount(300L), "pc", new Amount(100_000L),
    new Amount(300_000L), zero, zero, new Amount(300_000L));
CasInvoice invoice = new CasInvoice("INV-00000001", "20261029", uid, EisCodes.DOC_INVOICE,
    EisCodes.TRANS_VATABLE, "N", "", "", "",
    new SellerInfo("123456789", "00000", EisCodes.SELLER_VAT, "SAMPLE TRADING CORP.", "", "", "Makati City"),
    new BuyerInfo("987654321", "00000", "BUYER INC.", "", "", "", "", "", "", "", ""),
    List.of(item), new Amount(300_000L), new Discount(zero, zero, zero, zero, ""), zero,
    new Amount(300_000L), new Amount(36_000L), zero, zero, zero, zero, new Amount(336_000L), null, "AC-0001");
String json = EisJson.encode(invoice);   // "VATAmt":360.00
```

## Testing against the mock

```kotlin
MockEisServer(processingPolls = 1).use { mock ->
    mock.start()
    val client = EisClient(mock.credentials, sandboxSpacingMillis = 0)
    val ack = client.submit(listOf(json))
    client.inquire(ack.submitId)   // processStatusCode 02 (in progress)
    client.inquire(ack.submitId)   // 01, with SUC001 or the error code per invoice
}
```

The mock generates its own keys and credentials on each start. It checks the headers, the
clock (E05), the HMAC (E04), the RSA and AES layers (E21, E22), the portal login (E23), the
token (E03), submit IDs (E11, E14, E15), JWS signatures and key ID (SYN002), the schema and
formulas (SYN004, ERR002 to ERR014), duplicates (SYN003), the EIS Cert Number inside the ID
(ERR006), and that a credit memo's original was registered (ERR007).

To test a client written in another language, run the mock on its own. It writes a credentials
file and keeps running until stopped:

```bash
MOCK_EIS_PORT=8787 ./gradlew :bir-einvoice-mock:run --args="mock-eis-credentials.json"
```

## Crypto, as BIR specifies it

| Step | Algorithm |
|---|---|
| Authentication request `data` | RSA/ECB/PKCS1Padding with the EIS public key, over `{"userId","password","authKey"}` |
| `authKey` | 32 random characters from `0-9A-Za-z@#$%^&*()` (SecureRandom) |
| Authentication response, submit request and submit response `data` | AES/CBC/PKCS5Padding, key = the 32 UTF-8 bytes of the key, IV = its first 16 bytes, base64 |
| `authorization` header | `Bearer ` + base64(HmacSHA256(key, datetime + METHOD + path)); the application key for authentication, the session key otherwise |
| `datetime` header | `yyyyMMddHHmmss` in Philippine time (UTC+8) |
| Invoice signature | compact JWS, RS256, header `{"kid":...,"alg":"RS256"}`, base64url without padding |

BIR's protocol derives the IV from the key. That is BIR's choice, so use these keys for EIS and
nothing else.

## Error codes

Request errors come back as `EisException.code`. The client also uses `HTTP` (a response that
is not EIS JSON), `HTTP<status>` (an error with no code), `NETWORK` (no response) and `RESPONSE`
(a response it could not decrypt or read).

| Code | Meaning and fix |
|---|---|
| E01 | EIS Cert Number not recognised, or not registered for production |
| E02 | Application ID not recognised for this EIS Cert Number |
| E03 | Auth token expired (6 hours) or invalid. The client signs in again and retries once, with the same submit ID |
| E04 | HMAC wrong: application key for authentication, session key for other calls |
| E05 | `datetime` is not current Philippine time, is in the wrong format, or is too far off |
| E06 | EIS key pair expired (BIR: does not occur) |
| E07 | Production call from an IP that is not whitelisted (4 IPs per application) |
| E08 | Sandbox locked once the EIS certificate is requested. Create a new application to keep testing |
| E11 | Request body JSON does not match the guide |
| E12, E13 | Invalid or expired eisKeyId (BIR: does not occur) |
| E14 | Duplicate submit ID |
| E15 | Submit ID unknown, expired, or not yours |
| E16 | No result document (BIR: does not occur) |
| E17 | Callback URL not responding |
| E18 | Callback returned the wrong response shape |
| E19 | Sandbox called more than once per 10 seconds |
| E21 | `data` could not be decrypted: wrong key or algorithm |
| E22 | `data` decrypted but is not valid JSON |
| E23 | Wrong EIS Certification Portal user ID or password |
| E24 | `authKey` is not a 32-character string |
| E31 | Invalid callback response body (BIR: does not occur) |
| E99 | EIS internal error. Send the full request headers and body to the EIS team |

Per-invoice results come back in `InquiryResult.processedDocuments[].resultStatusCode`:

| Code | Meaning |
|---|---|
| SUC001 | Passed |
| SYN002 | Invalid digital signature |
| SYN003 | Duplicate EisUniqueId |
| SYN004 | Schema error: missing or extra fields, codes, types, number format |
| ERR001 | Seller TIN not registered |
| ERR002 | Issue date invalid, after transmission, or different from the date inside EisUniqueId |
| ERR003 | Transmission date invalid |
| ERR004 | Sales and VAT have different signs, or VAT is not 0 on zero-rated or exempt sales |
| ERR005 | Classification code error |
| ERR006 | EIS Cert Number inside EisUniqueId differs from the sender's |
| ERR007 | PrevUniqueId missing or not the seller's, or present on a document that is not a correction |
| ERR011 | Invalid document type |
| ERR012 | Invalid seller type |
| ERR013 | RBE local sale from a seller that is not an RBE |
| ERR014 | Invalid transaction classification |
| ERR015 | TIN and branch code are not an RBE |
| ERR999 | Other |

Both tables are also in code: `EisCodes.requestErrors` and `EisCodes.resultCodes`.

## Official sources

* EIS e-invoice API Development Guide v2.3 and e-invoice JSON File Format v2.1 (CAS sheet v2.2):
  <https://eis-cert.bir.gov.ph/#/downloads/1331>
* Revenue Memorandum Circular No. 98-2026:
  <https://bir-cdn.bir.gov.ph/BIR/pdf/RMC%20No.%2098-2026%20redacted.pdf>
* EIS Certification Portal downloads (public key, API Error Solution Table, sample code):
  <https://eis-cert.bir.gov.ph/#/downloads>

This repository links to BIR documents and does not redistribute them.
[docs/EIS-API-SPEC.md](docs/EIS-API-SPEC.md) is EveryTech's own digest of them and
[docs/eis-api.openapi.yaml](docs/eis-api.openapi.yaml) an OpenAPI description written from the
guide. When they disagree with BIR's documents, BIR's documents win.

## Reporting bugs

Open an issue with the
[bug report form](https://github.com/everytech-io/bir-einvoice/issues/new?template=bug_report.yml).
It asks for the library version, your Kotlin or Java version, the EIS error code, and a minimal
code snippet. A snippet that fails against `MockEisServer` is the quickest to fix.

Issues are public. Leave out TINs, customer details, real invoices, EIS credentials, keys and
tokens; use made-up values. Security problems never go in a public issue: see
[SECURITY.md](SECURITY.md). Problems in the desktop app belong in
[everytech-io/einvoice](https://github.com/everytech-io/einvoice/issues).

## Building

```bash
./gradlew check                  # all tests
./gradlew publishToMavenLocal    # io.everytech:bir-einvoice:0.1.0 and bir-einvoice-mock:0.1.0
```

Two interop tests run only when asked. They check this library against EveryTech's separate
Node.js implementation of the same protocol:

* `BIR_EINVOICE_NODE_MOCK_CREDS=<credentials.json>`: the Kotlin client against a running Node mock.
* `BIR_EINVOICE_NODE_DIR=<folder with src/eis/client.mjs>`: the Node client against `MockEisServer`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
