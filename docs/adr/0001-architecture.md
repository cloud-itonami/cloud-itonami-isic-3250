# ADR-0001: MedInstrAdvisor ⊣ Medical Instrument Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-3250` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-3250` publishes an OSS blueprint for medical-and-
dental-instrument **plant operations coordination** (production-batch
device-class/sterility-assurance-level/quantity/nonconformance-rate
data logging, machining/molding/sterilization-equipment maintenance
scheduling, safety-concern flagging, and outbound product shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2211` (Manufacture of
rubber tyres and tubes): both are back-office coordination actors for
a fixed processing PLANT with heavy manufacturing equipment and a real
physical/patient-safety dimension, and both share the same four-op
shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
`cloud-itonami-isic-2211`'s architecture closely (itself informed by
`cloud-itonami-isic-2013` and `cloud-itonami-isic-2220`) but adapts the
hazard profile and equipment/product vocabulary to the medical/dental-
instrument plant: this vertical's central physical hazard is
machining/molding/sterilization-equipment operation and sterility-
assurance failure (patient-infection risk, not 2211's high-temperature/
high-pressure curing-press hazard); its permanent equipment-actuation
block guards machining/molding/sterilization EQUIPMENT
(`:actuate-equipment?`) rather than a building/curing/vulcanization
LINE (`:actuate-line?`); its production-batch record declares a
`:device-class` (closed set spanning the FDA's/EU MDR's class-i/
class-ii/class-iii risk-classification scheme) and a
`:sterility-assurance-level` (the SAL exponent under ISO 14937/ISO
11135, plausibility-checked 1-12) in addition to a
`:nonconformance-rate-percent`, rather than 2211's `:tyre-category`/
`:load-index`/`:defect-rate-percent`; and its shipment quantity is
tracked in finished-instrument UNITS (`:units`/`:quantity-units`/
`:shipped-units`), the same counted-not-weighed shape 2211 uses for
finished tyres.

This vertical additionally has a DOMAIN-SPECIFIC permanent block
mirroring 2211's DOT/ECE block but for a different regulatory regime:
manufacture of medical and dental instruments is subject to FDA
510(k) premarket-notification clearance (US, 21 CFR Part 807 Subpart
E) and EU MDR CE-marking conformity-assessment regimes (EU,
Regulation (EU) 2017/745). This actor is never the regulatory-
clearance authority — any proposal (regardless of op) that declares
`:issue-clearance? true` is a HARD, PERMANENT, unconditional block
(`medinstrmfg.governor/clearance-authority-blocked-violations`), the
same "no phase, no human override" posture as the equipment-actuation
block.

This vertical has NO pre-existing `kotoba-lang/medinstrmfg`-style
capability library to wrap (verified: no such repo exists, and no
`meddevice`/`medinstr`-named repo exists in `kotoba-lang` either, via
GitHub code/repo search). This build therefore uses self-contained
domain logic — pure functions in `medinstrmfg.registry` (equipment/
batch verification, shipment-quantity recompute, device-class
validation, sterility-assurance-level plausibility validation,
nonconformance-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2211`'s `tyremfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:medical-instrument-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"medical-instrument-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external medical-instrument-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
medical-and-dental-instrument vertical has NO pre-existing capability
library to wrap. The equipment/batch-verification / shipment-quantity
/ device-class / sterility-assurance-level / nonconformance-rate
validation functions live as pure functions in `medinstrmfg.registry`
and are re-verified independently by `medinstrmfg.governor` — the
same "ground truth, not self-report" discipline established across
prior actors (most directly `cloud-itonami-isic-2211`'s
`tyremfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of medical-and-
dental-instrument plant operations. It does NOT:
- Control machining, molding, or sterilization equipment directly
- Make plant-safety or regulatory-clearance decisions (exclusive to the human plant supervisor / accredited regulatory body)
- Actuate machining/molding/sterilization equipment
- Self-issue an FDA 510(k) clearance or CE conformity mark

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the regulatory body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: medical and dental instrument
manufacturing is a safety-critical domain (sterility-assurance
failure risk, biocompatibility hazard, FDA 510(k)/CE-mark regulatory
clearance, direct patient-safety consequence downstream).
Safety-concern flagging NEVER auto-commits. All safety concerns
escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (sterility-validation-failure concern,
materials-biocompatibility concern, device-defect concern) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" —
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2211`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether
a batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity — never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `medinstrmfg.governor`, mirroring `cloud-itonami-isic-2211`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct machining/molding/sterilization-equipment control, equipment actuation, or self-issued FDA 510(k)/CE-mark regulatory clearance is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Medical-and-dental-instrument plant operations back-office now has
a documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no regulatory clearance mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or regulatory-
clearance self-issuance. Safety concerns are a circuit-breaker, not a
threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and regulatory
clearance issuance remain human-/institution-controlled via external
channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, regulatory-body APIs) —
this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-3250`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, clearance-authority-blocked, already-scheduled,
  invalid-device-class, invalid-sterility-assurance-level,
  invalid-nonconformance-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
