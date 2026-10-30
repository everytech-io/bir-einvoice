# Governance

This document says who decides what in bir-einvoice, and how that can change. The project is small,
and the rules are meant to stay small too.

## Roles

**Users** run or use the software. Their bug reports, questions and feedback shape it.

**Contributors** send issues, pull requests, reviews, documentation or translations. Everyone who
follows the [Code of Conduct](CODE_OF_CONDUCT.md) and signs off their commits
([DCO](CONTRIBUTING.md#sign-off-your-commits-developer-certificate-of-origin)) can contribute.

**Maintainers** have write access, review and merge pull requests, triage issues, cut releases,
and handle security reports. Maintainers are listed in [CODEOWNERS](.github/CODEOWNERS).

Current maintainers:

| Name | GitHub | Affiliation |
|---|---|---|
| JE Ramos | [@JE-Ramos](https://github.com/JE-Ramos) | Lead maintainer; does business as EveryTech |

The project has one maintainer today. That is a risk we want to reduce; see "Becoming a
maintainer".

## EveryTech's role

EveryTech is the trade name of JE Ramos, a freelance developer, who started the project,
publishes the releases and holds the EveryTech name and logo (see [TRADEMARKS.md](TRADEMARKS.md)).
EveryTech is not a company. The `everytech-io` GitHub organization that hosts this repository is
EveryTech's. In practice:

* Anyone can become a maintainer on the merits, as described in "Becoming a maintainer".
* EveryTech does not own contributors' copyright. Contributors keep it and license their work
  under Apache-2.0.
* Roadmap, review and merge decisions are made in the open, in issues and pull requests, under
  the rules below. EveryTech's commercial interests do not override the accuracy rules in
  [CONTRIBUTING.md](CONTRIBUTING.md).
* The project stays Apache-2.0. A relicensing would need the agreement of every contributor
  whose work would be affected, because there is no contributor license agreement that allows
  otherwise.
* EveryTech runs no service for users of this project and does not receive their data.

## How decisions are made

* **Most changes** are decided by lazy consensus. A pull request that has a maintainer's
  approval, passing checks and no unresolved objection can be merged. Silence for 7 days after a
  reasonable request for review is not approval; ask again or ask another maintainer.
* **A maintainer does not merge their own substantial change** without another maintainer's
  review when there is another maintainer. While there is only one, they say so in the pull
  request, wait at least 48 hours for comments, and cite the BIR source for any rule.
* **Accuracy against the BIR's documents wins over convenience.** If a change conflicts with an
  official BIR document, it does not go in. If the documents are unclear, we ask in an issue and
  record the decision.
* **Larger decisions** (public API changes, a new dependency, a new platform target, security
  design, release policy) are proposed in an issue, with the reasons and the alternatives, and
  stay open for at least 7 days. Maintainers decide by consensus; if they cannot agree, the lead
  maintainer decides and records the reasoning in the issue.
* **Disagreements** are about the work, not people. Keep to the Code of Conduct. If a
  discussion goes in circles, any maintainer can ask for a summary and a decision.

## Becoming a maintainer

There is no quota and no fixed timetable. A contributor can be proposed, by themselves or by a
maintainer, after a sustained record of:

* useful contributions over several months (code, review, documentation, triage);
* careful work with the BIR sources and the tests;
* good judgment and good conduct in discussions;
* review of other people's pull requests.

A current maintainer opens a private discussion among maintainers (or an issue, if the candidate
agrees). If the maintainers agree, the person is invited, accepts, and is added to CODEOWNERS and
the table above with a pull request. Two factor authentication on GitHub is required.

Maintainers who are inactive for 12 months are asked if they want to continue, and are moved to
an "emeritus" list if they do not answer or prefer to step down. They are thanked, and can return
by asking.

## Removing a maintainer

A maintainer who breaks the Code of Conduct, or repeatedly acts against the rules above, can be
removed by the other maintainers. While there is a single maintainer, no one else can make that
decision. Conduct reports about the maintainer still go to contact@everytech.io.

## Code of Conduct enforcement

Reports go to contact@everytech.io, as described in the [Code of Conduct](CODE_OF_CONDUCT.md).
Where a report is about a maintainer, that maintainer takes no part in handling it.

## Changing this document

Open a pull request. Governance changes need approval from all maintainers and stay open for at
least 14 days.
