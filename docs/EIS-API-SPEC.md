# BIR EIS API: spec digest

A working summary of the BIR Electronic Invoicing System (EIS) API, so we can build against it
without reopening a 64-page PDF every time. It is a digest, not the spec. When they disagree,
the spec wins, and the official portal wins over any mirror.

Machine-readable version: [`eis-api.openapi.yaml`](eis-api.openapi.yaml) (OpenAPI 3.0, opens in
Swagger UI or Swagger Editor).

## Where the spec lives

All of these are on the official EIS Certification Portal Downloads page,
<https://eis-cert.bir.gov.ph/#/downloads>. We link to them; we do not redistribute them.

| Download post | File | Version | Date |
|---|---|---|---|
| [1331](https://eis-cert.bir.gov.ph/#/downloads/1331) | EIS e-invoice API Development Guide | **2.3** | 30 Jul 2026 (posted 15 Oct 2026) |
| [1331](https://eis-cert.bir.gov.ph/#/downloads/1331) | Annex B, e-invoice JSON File Format (BIR change request) | **2.1** (CAS sheet v2.2, POS sheet v2.1) | 26 Sep 2026 |
| 1092 | Sworn Statement pro forma (for PTT) | | |
| 1074 | API Error Solution Table | 1.1 | |
| 862 | API Call Sample Code (Java, Python, C#) | | |
| 19 | EIS Certification User Guide | 1.2 | 20 May 2022 |
| 16 | EIS Certification Public Key | | |

The CAS sheet of the 2.1 file is headed "PROPOSED CHANGES" and dated 26 Sep 2026. Treat its
changes as the direction of travel, and re-check the Downloads page before certification.
Older copies (guide 2.0, JSON 2.01) circulate on third-party mirrors such as bir-eis.ph. Do not
build from them.

FAQ for API errors: <https://eis-cert.bir.gov.ph/#/support/faq>. FAQ for invoice issuance on the
production portal: <https://eis.bir.gov.ph/#/support/faq?tabType=ISSUANCE>.

## What changed in 2.3 / 2.2 (and what it means for us)

| Change | Effect on the build |
|---|---|
| "Accreditation ID" renamed **EIS Cert Number**. The header is still `accreditationId`. | UI says "EIS Cert Number"; code keeps the header name. |
| CAS `DocType` is now `01` Invoice, `03` Credit Memo/Note. Service Billing `04` and Official Receipt `05` deleted; Debit Memo `02` removed in JSON 2.1. | We emit only `01` and `03`. An increase is a new invoice (RMC 98-2026 says the same). |
| New `TransClass` `04`: local sales of Registered Business Enterprises (RBE). New seller `Type` `2`: RBE. | Not in v0.1. This tool targets regular VAT sellers (`Type` `0`). |
| `BusinessNm` now optional; item `Desc` now mandatory. | We always send both. `Desc` falls back to the item name. |
| New errors E17, E18, E19, E31 and ERR011 to ERR015. | Mapped in the client and the UI. |
| Sandbox: **one call per 10 seconds** (E19). | The certification runner waits between calls. |
| Sample code now ships in Java, Python, and C#. | Our crypto is checked against what those samples do. |

## The big picture

```
 Seller's own server (whitelisted IP)                           BIR EIS
 1. POST /api/authentication ── RSA(EIS public key) ─────────▶  check creds + HMAC
                             ◀─ AES(authKey){authToken, sessionKey, tokenExpiry}
 2. POST /api/invoices       ── AES(sessionKey)(JWS,JWS,…) ──▶  ack at once
                             ◀─ AES(sessionKey){ackId, refSubmitId}
 3. GET  /api/invoice_result/{submitId} ─────────────────────▶  poll until done
                             ◀─ {processStatusCode, processedDocuments[]}
 (4) optional: EIS ── PUT {seller}/api/receive_invoice_result ─▶ callback
```

* Sandbox base URL: `https://eis-cert.bir.gov.ph` (from the 2.3 guide and the official samples).
  The production base URL is not in the guide; take it from the portal once the PTT is issued.
* Token lifetime: 6 hours. Reuse it.
* 1 to 100 e-invoices per submission; 1 to 1,000 line items per e-invoice.
* Results are not instant ("EIS will process the final registration within the next business
  day"). Poll the inquiry endpoint.

## Credentials (issued by the EIS-Cert portal to the account holder)

| Item | What it is | Holder |
|---|---|---|
| EIS Cert Number (`accreditationId`) | 8 chars; first 2 digits are the source: `10` EIS web portal, `20` CAS, `31` POS, `32` CRM, `51` ASP-CAS, `52` ASP-POS | seller |
| `applicationId` | the application created in the portal | seller |
| Application key | HMAC key for the authentication call only | seller |
| `userId` / `password` | EIS-Cert portal login | seller |
| EIS public key | RSA public key, X.509 SPKI base64; published on Downloads (post 16) | public |
| Digital signing key pair + key ID | RSA private key, PKCS#8 base64, for signing; key ID goes in the JWS `kid` | seller only |
| IP whitelist | production only; up to 4 IPs per application; others get `E07` | seller's server |

The portal has two account types. A **Taxpayer** issues and transmits invoices. A **SW Provider**
"tests the transmission function and supplies the SW to the taxpayer without transmitting the
actual invoice/receipt to the EIS". Certification is per server: each taxpayer certifies the
server it runs.

## Request headers

| Header | Auth | Others | Value |
|---|---|---|---|
| `accreditationId` | yes | yes | EIS Cert Number |
| `applicationId` | yes | yes | application ID |
| `datetime` | yes | yes | `yyyyMMddHHmmss`, Philippine time. Guide: within 10 minutes; error table: 15 |
| `authorization` | yes | yes | `Bearer ` + base64(HMAC-SHA256(key, datetime + METHOD + path)) |
| `authToken` | no | yes | from the auth response |
| `Content-Type` | yes | yes | `application/json; charset=utf-8` |

HMAC key: the application key for authentication, the session key for everything else. The path
is the URL path without the host, for example `/api/invoice_result/20111111-20261029-0a1b2c3d4e5f`.

## Crypto, as the guide and the official samples do it

| Step | Algorithm | Detail |
|---|---|---|
| Auth request `data` | RSA PKCS#1 v1.5 | encrypt UTF-8 JSON `{"userId","password","authKey"}` with the EIS public key; base64 |
| `authKey` | random | exactly 32 characters (guide alphabet `0-9A-Za-z@#$%^&*()`) |
| Auth response `data` | AES-256-CBC, PKCS#5/7 padding | key = the 32 bytes of `authKey`; IV = its first 16 characters; base64 |
| Invoice signature | JWS compact, `RS256` | header `{"kid": <key ID>, "alg": "RS256"}`; payload = the invoice JSON, UTF-8 |
| Submit `data` | AES-256-CBC | plaintext = JWS strings joined with `,`; key = session key; IV = first 16 chars of the session key |
| Submit response `data` | AES-256-CBC | session key, as above |
| Inquiry response `data` | none | a plain JSON object in both the guide and the Python sample |

The IV is derived from the key. That is BIR's design; we implement it as specified and use those
keys for nothing else. The official samples send `forceRefreshToken` as the string `"false"`; the
guide types it as Boolean. We send a Boolean.

## Endpoints

| Call | Method + path | Body |
|---|---|---|
| Authentication | `POST /api/authentication` | `{"data": <RSA b64>, "forceRefreshToken": false}` |
| Invoice issuance | `POST /api/invoices` | `{"submitId": <id>, "data": <AES b64>}` |
| Inquiry result | `GET /api/invoice_result/{submitId}` | none |
| Result callback (optional, served by us) | `PUT {our URL}/api/receive_invoice_result` | result JSON |

`submitId`: `<EIS Cert Number>-<YYYYMMDD>-<12 lowercase hex>`.

Every response is `{"status": "1", "data": ...}` or
`{"status": "0", "errorDetails": {"errorCode", "errorMessage"}}`.

* Auth `data` (decrypted): `accreditationId`, `userId`, `authToken`, `sessionKey`, `tokenExpiry`
  (`yyyy-MM-ddThh:mm:ss`).
* Submit `data` (decrypted): `accreditationId`, `userId`, `refSubmitId`, `ackId`
  (`BIR-yyyyMMddhhmmss-XXXXX`), `responseDtm`, `description`.
* Inquiry `data`: `processStatusCode` (`01` done, `02` in progress, `03` cannot process),
  `failReasonStatusCode` (`FRS001` unknown submitId, `FRS002` decryption failure), and when done
  `totalCountQuantity`, `successCountQuantity`, `failCountQuantity`,
  `processedDocuments[] {invoiceUid, resultStatusCode, description}`.

### Request errors

| Code | Meaning (from the API Error Solution Table) |
|---|---|
| E01 | Unknown EIS Cert Number, or not registered for production |
| E02 | Unknown applicationId, or not under this EIS Cert Number |
| E03 | authToken expired (6 h) or invalid: authenticate again |
| E04 | HMAC wrong: application key for auth, session key otherwise |
| E05 | datetime not Philippine time, wrong format, or more than 15 minutes off |
| E06 | Expired EIS key pair ("does not occur") |
| E07 | Production call from a non-whitelisted IP (4 IPs per application) |
| E08 | Sandbox locked once the certificate is requested; make a new application to keep testing |
| E11 | Request body JSON does not match the spec |
| E12 / E13 | Invalid / expired eisKeyId ("does not occur") |
| E14 | Duplicate submitId |
| E15 | submitId unknown or not yours |
| E16 | No result document ("does not occur") |
| E17 | Callback URL not responding |
| E18 | Callback returned the wrong response shape |
| E19 | Sandbox called more than once per 10 seconds |
| E21 | `data` could not be decrypted (wrong key or algorithm) |
| E22 | `data` decrypted but is not valid JSON |
| E23 | Wrong EIS-Cert user ID or password |
| E24 | authKey is not a 32-character string |
| E31 | Invalid callback response body ("does not occur") |
| E99 | EIS internal error: send the full request headers and body to the EIS team |

### Per-invoice result codes

| Code | Meaning |
|---|---|
| SUC001 | Passed |
| SYN002 | Invalid digital signature |
| SYN003 | Duplicate EisUniqueId |
| SYN004 | Schema error: missing or extra fields, codes, types, number format |
| ERR001 | Seller TIN not registered |
| ERR002 | Issue date invalid, after transmission, or different from the date inside EisUniqueId |
| ERR003 | Transmission date invalid |
| ERR004 | Sales and VAT have different signs, or VAT is not 0 on exempt or zero-rated |
| ERR005 | Classification code error |
| ERR006 | EIS Cert Number inside EisUniqueId differs from the sender's |
| ERR007 | PrevUniqueId missing or not the seller's, or present on a non-correction |
| ERR011 | Invalid Document Type |
| ERR012 | Invalid Seller Type |
| ERR013 | RBE local sale from a seller that is not an RBE |
| ERR014 | Invalid Transaction Classification |
| ERR015 | TIN and branch code are not an RBE |
| ERR999 | Other |

## e-invoice JSON, CAS variant (sheet v2.2)

Numbers carry 2 decimals and may be negative. An empty string is `""` or `null`; an empty number
is `0.00`. All amounts are in PHP.

### Header

| Field | Type / max | Req | Notes |
|---|---|---|---|
| `CompInvoiceId` | String 50 | Y | the seller's own invoice or credit memo number |
| `IssueDtm` | String 8 | Y | `YYYYMMDD` |
| `EisUniqueId` | String 24 | Y | issue date (8) + EIS Cert Number (8) + 8 chars `[A-Z0-9]` |
| `DocType` | String 2 | Y | `01` Invoice, `03` Credit Memo/Note |
| `TransClass` | String 2 | Y | `01` VATable, `02` Zero-rated, `03` VAT-exempt, `04` RBE local sales |
| `CorrYN` | String 1 | Y | `Y` on a correcting or adjusting document |
| `CorrectionCd` | String 2 | if CorrYN=Y | `01` error, `02` duplication, `03` addition/reduction, `04` cancellation, `05` return, `09` others |
| `PrevUniqueId` | String 24 | if CorrYN=Y | EisUniqueId being corrected; 24 zeros if the original was never in this system |
| `Rmk1` | String 500 | N | explanation; extra PrevUniqueIds when one memo corrects several invoices |

### SellerInfo (required) and BuyerInfo (required)

Seller: `Tin` (9 digits), `BranchCd` (5 digits; branch `101` → `00101`), `Type` (`0` VAT, `1`
non-VAT, `2` RBE), `RegNm` (200), `BusinessNm` (200, optional), `Email` (100, optional),
`RegAddr` (300). All must match BIR Form 2303.

Buyer: `Tin` (`000000000` if none), `BranchCd`, `RegNm`, `BusinessNm` (optional), `Email`,
`RegAddr`, `DevAddr`, and for exports `AirNum`, `AirNumDt`, `LadNum`, `LadNumDt` (all optional).

### ItemList (1 to 1,000)

| Field | Req | Rule |
|---|---|---|
| `Nm` | Y | item name, 100 |
| `Desc` | **Y** (was optional) | description, 100 |
| `Qty` | Y | 2 decimals allowed |
| `Unit` | N | unit of measure, 50 |
| `UnitCost` | Y | per unit, net of VAT |
| `SalesAmt` | Y | Qty × UnitCost, net of VAT |
| `RegDscntAmt` | Y | outright discount (reduces the VAT base) |
| `SpeDscntAmt` | Y | conditional discount (income tax only) |
| `NetSales` | Y | SalesAmt − (RegDscntAmt + SpeDscntAmt); special discount ignored for VATable |

### Totals

| Field | Rule |
|---|---|
| `TotNetItemSales` | sum of NetSales |
| `Discount` {`ScAmt`, `PwdAmt`, `RegAmt`, `SpeAmt`, `Rmk2`} | document-level discounts |
| `OtherTaxRev` | other taxable revenue |
| `TotNetSalesAftDisct` | TotNetItemSales + OtherTaxRev − (ScAmt + PwdAmt + RegAmt + SpeAmt) |
| `VATAmt` | 0 when TransClass is `02` or `03` |
| `WithholdIncome`, `WithholdBusVAT`, `WithholdBusPT` | amounts the buyer withholds |
| `OtherNonTaxCharge` | non-taxable charges (local tax, DST, overseas communications tax) |
| `NetAmtPay` | amount the buyer pays |
| `ForCur` (optional) | `Currency`, `ConvRate`, `ForexAmt` |
| `PtuNum` | PTU or Acknowledgment Certificate number of the CAS |

### Mixed invoices

One CAS invoice that mixes classes goes to EIS as one e-invoice per `TransClass`, all with the
same `CompInvoiceId`, each with its own `EisUniqueId`.

### Credit memos and corrections (BIR "Correction Scenarios" sheet)

* Credit memos carry **negative** amounts (`CM -200`), with `CorrYN` `Y`, a correction code, and
  `PrevUniqueId` of the invoice they correct.
* Error: reverse with a negative document or a CM, then issue a new invoice.
* Duplication: the reversing document is dated the **same day as the original**.
* Addition/reduction: dated when the change happened. Cancellation: when the contract was
  cancelled. Return: when the return happened.
* Corrections to documents dated before the PTT approval date are not transmitted.

## Certification flow (seller's own account)

1. Sign up at <https://eis-cert.bir.gov.ph> as a **Taxpayer**, using BIR Form 2303 details. Attach a
   Secretary's Certificate or Board Resolution (non-individual) or an SPA (individual). BIR approves.
2. Create an application. It is disabled if certification is not done within 6 months.
3. Generate the application key, then the digital signing key pair. Download the credentials file
   and keep it offline.
4. Request a sandbox; run the mandatory tests (5, or 7 with callback), one call per 10 seconds.
5. Request the EIS Certificate. BIR reviews the test invoices. The sandbox then locks (E08).
6. Permit to Transmit (PTT), only once the Commissioner directs e-sales reporting (RMC 98-2026).
   Documents: Risk and Disaster Management Plan, Software Features and Specifications, Sworn
   Statement (pro forma on Downloads), IT Systems Configuration Diagram, System/Data/Process Flow.
   Register up to 4 production IPs.

RMC 98-2026 order of events: Permit to Issue (PTI) Electronic Invoice from the RDO or LT Office
first (20 working days on a complete file), then EIS Certification within 6 months of the PTI.
