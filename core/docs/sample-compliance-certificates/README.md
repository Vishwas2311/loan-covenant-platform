# Sample Compliance Certificates (CP-1001 … CP-1015)

These are **sample compliance-certificate documents**, one per demo Credit Proposal, for use with the
CP-level **Compliance Certificate** upload (US-MON-17) in the RM assessment workspace. They mirror the
borrower, facility purpose and segment of each demo CP (see `wiremock/mappings/demo-cp-*.json`).

## What a compliance certificate is
A periodic letter the **borrower's CFO / Company Secretary** issues to the lender, certifying whether
the financial and non-financial covenants attached to a facility were complied with for a reporting
period, with the computed ratio values and an explanation for any breach.

## How to use them
1. Start the app in dev (`-Dspring-boot.run.profiles=dev`, port 9310) and open a CP in the Monitoring
   workspace → **CP assessment** screen.
2. In the **Compliance Certificate** panel, upload the matching `CP-10xx.md` (or export it to PDF
   first). The file is forwarded to the file-upload service (WireMock in dev, `file-upload.json`),
   which returns a `fileId` persisted in `cov_compliance_certificate`.
3. The stored `fileId` is one of the inputs sent to the AI service for status suggestions (US-MON-18).

> These are **fictional samples for testing/KT only** — names, figures and signatories are illustrative.

## Index
| CP | Borrower | Facility purpose | Segment / sector |
|----|----------|------------------|------------------|
| CP-1001 | Reliance Industries Ltd | Jamnagar Refinery Expansion | CONGLOMERATE / OIL_GAS |
| CP-1002 | Reliance Industries Ltd | Jio 5G Network Rollout | CONGLOMERATE / TELECOM |
| CP-1003 | Reliance Industries Ltd | Reliance Retail Store Expansion | CONGLOMERATE / RETAIL |
| CP-1004 | Tata Steel Ltd | Kalinganagar Phase-2 Expansion | METALS / STEEL |
| CP-1005 | Tata Steel Ltd | European Ops Refinancing | METALS / STEEL |
| CP-1006 | Adani Ports and SEZ Ltd | Mundra Terminal Expansion | INFRASTRUCTURE / PORTS |
| CP-1007 | Adani Ports and SEZ Ltd | Vizhinjam Port Development | INFRASTRUCTURE / PORTS |
| CP-1008 | Bharti Airtel Ltd | 5G Spectrum Financing | TELECOM |
| CP-1009 | Bharti Airtel Ltd | Navi Mumbai Data Center | TELECOM / DATA_CENTER |
| CP-1010 | Larsen and Toubro Ltd | Mumbai-Ahmedabad HSR Package | INFRASTRUCTURE / ENGINEERING |
| CP-1011 | Larsen and Toubro Ltd | Defence Systems Manufacturing | INFRASTRUCTURE / DEFENCE |
| CP-1012 | JSW Steel Ltd | Dolvi Works Expansion | METALS / STEEL |
| CP-1013 | JSW Steel Ltd | Green Steel Transition | METALS / STEEL |
| CP-1014 | Mahindra and Mahindra Ltd | EV Manufacturing Plant Pune | MANUFACTURING / AUTOMOTIVE |
| CP-1015 | Mahindra and Mahindra Ltd | Farm Equipment Export Finance | MANUFACTURING / AUTOMOTIVE |

Reporting period for all samples: **quarter ended 31 March 2026**; certificate date **09 April 2026**.
