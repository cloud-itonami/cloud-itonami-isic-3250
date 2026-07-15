(ns medinstrmfg.registry
  "Pure-function domain logic for the medical-and-dental-instrument
  plant-operations coordination actor -- equipment/batch verification,
  shipment-quantity recompute, device-class validation, sterility-
  assurance-level plausibility validation, nonconformance-rate
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/medinstrmfg`-style capability library to
  wrap (verified: no such repo exists, and no `meddevice`/`medinstr`-
  named repo exists in kotoba-lang either). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `medinstrmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `tyremfg.registry/shipment-quantity-exceeded?` from
  `cloud-itonami-isic-2211`, and `resinmfg.registry` from
  `cloud-itonami-isic-2013`): never trust a proposal's own
  self-reported quantity/status when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating machining/molding/
  sterilization equipment or dispatching a real freight carrier, and
  never the act of issuing an FDA 510(k) clearance or a CE conformity
  mark (this actor NEVER does any of those -- see README `What this
  actor does NOT do`).

  SCOPE: ISIC 3250 covers manufacture of medical and dental instruments
  and supplies -- precision machining (surgical instruments, forceps,
  scalpels, dental hand instruments), molding (single-use plastic
  devices, dental impression trays), and sterilization-validation lines
  (autoclave/EtO processing, sterility-assurance testing) producing
  finished surgical and dental instruments and supplies. This actor
  coordinates the back-office record-keeping around that plant
  (production-batch logging, maintenance scheduling, safety-concern
  flagging, shipment coordination) -- it never touches the machining/
  molding/sterilization equipment directly, and it never stands in for
  the regulatory body (FDA / EU Notified Body) that issues 510(k)
  clearance / CE conformity marks.")

;; ----------------------------- constants -----------------------------

(def valid-device-classes
  "The closed set of device-class values a production-batch record may
  declare -- the FDA's three-tier medical-device risk classification
  (21 CFR Parts 862-892; the EU MDR's Class I/IIa/IIb/III scheme maps
  onto the same low->high risk ordering). Anything else is a
  fabricated/unrecognized device class -- the governor HARD-holds
  rather than let an invented classification pass through. This actor
  never DECIDES a device's classification (that is the manufacturer's
  regulatory-affairs function, reviewed by FDA/Notified Body); it only
  validates that a batch record declares one of the real, known
  values."
  #{:class-i :class-ii :class-iii})

(def sterility-assurance-level-min
  "Physical/regulatory floor for a batch's own sterility-assurance-
  level (SAL) exponent -- SAL is conventionally expressed as 10^-n; an
  exponent below 1 (i.e. a claimed sterility no better than 10^-0)
  is not a real terminal-sterilization claim."
  1)

(def sterility-assurance-level-max
  "Physical/regulatory ceiling for a batch's own SAL exponent. ISO
  14937 / ISO 11135 terminally-sterilized devices conventionally claim
  SAL 10^-6; exponents beyond 12 are beyond what any real validated
  sterilization cycle or bioburden test can substantiate -- an
  implausible/fabricated reading, not a real SAL claim."
  12)

(def nonconformance-rate-min-percent
  "Physical floor for a batch's own machining/molding/sterilization
  nonconformance-rate reading (zero nonconformances is the best
  possible outcome, never negative)."
  0.0)

(def nonconformance-rate-max-percent
  "Physical ceiling for a batch's own nonconformance-rate reading -- a
  batch cannot reject more than 100% of its own output. A reading
  above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its device-class/sterility-assurance-level/quantity/
  nonconformance-rate claims have actually been QC-inspected, not
  merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-quantity-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-units` + `new-units` exceed `batch`'s own recorded
  `:quantity-units` (the batch's own logged production quantity)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-units]
  (let [capacity (:quantity-units batch)
        so-far (:shipped-units batch 0.0)]
    (and (number? capacity)
         (number? new-units)
         (> (+ (double so-far) (double new-units)) (double capacity)))))

(defn device-class-valid?
  "Is `device-class` one of the closed, known device-class values?
  nil/blank is treated as invalid (a production-batch patch must
  declare a real device class, not omit it silently)."
  [device-class]
  (contains? valid-device-classes device-class))

(defn sterility-assurance-level-valid?
  "Is `sal` a physically/regulatorily plausible sterility-assurance-
  level exponent (the n in 10^-n)? Rejects nil, non-integers, values
  below `sterility-assurance-level-min`, and values beyond
  `sterility-assurance-level-max` -- a fabricated or sensor-error
  reading, never let through as a real sterilization-validation
  fact."
  [sal]
  (and (integer? sal)
       (>= sal sterility-assurance-level-min)
       (<= sal sterility-assurance-level-max)))

(defn nonconformance-rate-valid?
  "Is `percent` a physically plausible batch machining/molding/
  sterilization nonconformance-rate reading? Rejects nil, non-numbers,
  negative values, and values beyond `nonconformance-rate-max-percent`
  -- a fabricated or sensor-error reading, never let through as a real
  batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) nonconformance-rate-min-percent)
       (<= (double percent) nonconformance-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's. And NEVER an FDA 510(k) clearance / CE conformity mark --
  this actor is never the regulatory-clearance authority (see README
  `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  machining/molding/sterilization-equipment maintenance window against
  a verified, registered piece of equipment. Pure function -- does not
  actuate the machining/molding/sterilization equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `medinstrmfg.governor` independently re-verifies the equipment's own
  verified/registered ground truth, and permanently blocks any attempt
  to directly actuate machining/molding/sterilization equipment (see
  README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound medical/dental-instrument shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a plant coordinator would
  keep. `medinstrmfg.governor` independently re-verifies the
  shipment's own claimed quantity against `shipment-quantity-
  exceeded?`, before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
