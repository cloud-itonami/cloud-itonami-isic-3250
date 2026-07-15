# cloud-itonami-isic-3250: Manufacture of medical and dental instruments and supplies

Open Business Blueprint for **ISIC 3250**: manufacture of medical and dental instruments and supplies — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **medical/dental-instrument plant operations**: production-batch data logging (device-class/sterility-assurance-level/quantity/nonconformance-rate), machining/molding/sterilization-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for medical/dental-
instrument plant operations: run by a qualified operator so a plant
keeps its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not instrument-line control

ISIC 3250 covers the **manufacturing plant** that precision-machines
(surgical instruments, forceps, scalpels, dental hand instruments),
molds (single-use plastic devices, dental impression trays), and
sterilization-validates (autoclave/EtO processing, sterility-assurance
testing) the resulting surgical and dental instruments and supplies.
This actor coordinates the back-office record keeping around that
plant — it never touches the machining/molding/sterilization equipment
directly, and it is never the FDA (US) / EU Notified Body (EU)
regulatory-clearance authority.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — machining/molding/sterilization batch, output-quality/lot-traceability data logging (administrative, not an operational decision)
- `:schedule-maintenance` — machining/molding/sterilization-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a sterility-validation-failure/materials-biocompatibility/device-defect concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(machining/molding/sterilization-line equipment, patient-safety and
biocompatibility hazard, FDA 510(k)/CE-mark regulatory clearance,
direct patient-safety consequence):

- Does NOT control machining, molding, or sterilization equipment directly
- Does NOT make plant-safety or regulatory-clearance decisions (that's the plant supervisor's / regulatory body's exclusive human/institutional authority)
- Does NOT actuate machining/molding/sterilization equipment (human plant supervisor decides)
- Does NOT self-issue an FDA 510(k) clearance or CE conformity mark (the accredited regulatory body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and regulatory clearance requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`medinstrmfg.operation/build`, a langgraph-clj StateGraph):
1. **`medinstrmfg.advisor`** (sealed intelligence node, `MedInstrAdvisor`): proposes decisions only, never commits
2. **`medinstrmfg.governor`** (independent, `Medical Instrument Plant Operations Governor`): validates against domain rules, re-derived from `medinstrmfg.registry`'s pure functions and `medinstrmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct machining/molding/sterilization-equipment control)
     - Directly actuating machining/molding/sterilization equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an FDA 510(k) clearance / CE conformity mark (`:issue-clearance? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:device-class` value on a production-batch patch
     - No physically/regulatorily implausible `:sterility-assurance-level` value on a production-batch patch
     - No physically implausible `:nonconformance-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`medinstrmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`medinstrmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
