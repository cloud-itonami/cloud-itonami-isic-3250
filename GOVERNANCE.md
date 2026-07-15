# Governance

`cloud-itonami-isic-3250` is an OSS open-business blueprint for medical and dental instrument plant operations coordination.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a machining/molding/sterilization-equipment action the governor refuses is never dispatched to hardware.
- the Medical Instrument Plant Operations Governor remains independent of the advisor.
- hard policy violations (equipment-control bypass, equipment actuation, self-issued regulatory clearance, record-suppression, unauthorized disclosure) cannot be overridden by human approval.
- every schedule, sign-off, record and disclose path is auditable.
- sensitive operating and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing machining/molding/sterilization-equipment-control or record policy checks
- mishandling sensitive data
- misrepresenting certification status
- failing to respond to security or safety incidents
