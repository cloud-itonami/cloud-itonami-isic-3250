(ns medinstrmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL MedInstrOperationActor (`medinstrmfg.operation/build`
  -> a compiled langgraph-clj StateGraph) over the REAL seeded store
  (`medinstrmfg.store/sample-data!`), through the REAL Medical
  Instrument Plant Operations Governor (`medinstrmfg.governor/check`)
  and the REAL rollout phase gate (`medinstrmfg.phase/gate`), and
  renders whatever those produced. Nothing on this page is a
  hand-written result:

    - every table row is read back out of the store AFTER the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/shipment-history`, `store/maintenance-history`,
      `store/safety-concerns`),
    - every HARD-hold rule name and every violation detail string is
      the governor's OWN `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase-gate table is derived from `medinstrmfg.phase/phases`,
      and the governor configuration / ground-truth bound tables from
      the public vars of `medinstrmfg.governor` and
      `medinstrmfg.registry`.

  The ONLY hand-written content on the page is prose: section notes and
  the per-scenario `:exercises` sentence explaining what a request is
  meant to demonstrate. Those describe the FIXED op-gate contract this
  actor ships with (README `What this actor does` / `What this actor
  does NOT do`); they are documentation, not runtime telemetry, and
  they are never used to state an outcome -- every disposition, rule
  name, count and field value beside them comes from the run.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is either seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `machining-001` `sterilizer-002`
  -- verified against the seed before this file was written) or created
  by an op inside this demo itself -- `batch-004` exists only because
  the `t01` `:log-production-batch` commit created it, and every
  `mnt-*` / `ship-*` / `concern-*` subject is the draft record its own
  op registers via `medinstrmfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed shipment /
  maintenance record: `medinstrmfg.operation`'s `:request-approval`
  node puts the approver on the record's `:payload`, while
  `store/commit-record!` persists `:value` -- so the approver is shown
  from the run timeline (where it is real), not from the stored record
  (where it does not exist).

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [medinstrmfg.governor :as governor]
            [medinstrmfg.operation :as op]
            [medinstrmfg.phase :as phase]
            [medinstrmfg.registry :as registry]
            [medinstrmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`). `:exercises`
  is prose describing the fixed contract the request probes -- the
  OUTCOME is never written here, it is read back off the run."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch. Governor-clean, and :log-production-batch is the only member of phase 3's :auto set -> auto-commit. batch-004 exists for the rest of this page only because this op created it."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:device-class :class-ii
                      :lot-number "MDL-2026-004"
                      :sterility-assurance-level 6
                      :quantity-units 3000.0
                      :nonconformance-rate-percent 1.1
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against a verified + registered machining centre. Never auto-eligible at any phase -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "machining-001"
                      :maintenance-type :tool-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Safety concern. Always high-stakes, so the governor escalates regardless of confidence; the human approves. Never gated on the equipment being verified -- a concern is never blocked on an administrative technicality."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "sterilizer-002" :severity :moderate
                      :description "滅菌バリデーションの逸脱兆候、バイオバーデン再試験要"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered batch with headroom. Escalates; the human shipping approver approves and the batch's own shipped-units advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 500.0
                      :destination "buyer-hospital-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "Governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-002" :units 20.0
                      :destination "buyer-hospital-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth of its own. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :units 10.0
                      :destination "buyer-hospital-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment against the seeded UNVERIFIED / unregistered batch. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-003" :units 100.0
                      :destination "buyer-hospital-south"}}}

   {:tid "t08"
    :exercises "Shipment whose claimed units would push batch-002 past its own recorded production quantity. The governor recomputes headroom from the batch's own fields, never from the proposal's claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-002" :units 100.0
                      :destination "buyer-hospital-east"}}}

   {:tid "t09"
    :exercises "Maintenance against the seeded sterilizer, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "sterilizer-002"
                      :maintenance-type :cycle-validation
                      :scheduled-date "2026-08-05"
                      :actuate-equipment? false}}}

   {:tid "t10"
    :exercises "A maintenance proposal that tries to ACTUATE the machining centre rather than draft a window. Permanent scope boundary -- never reaches a human, even though this scenario offers an approval. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "machining-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-equipment? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t11"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "machining-001"
                      :maintenance-type :tool-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}}

   {:tid "t12"
    :exercises "A batch patch declaring a device class outside the FDA's three-tier risk classification. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:device-class :class-ix}}}

   {:tid "t13"
    :exercises "A batch patch claiming a sterility-assurance level no validated sterilization cycle can substantiate. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:sterility-assurance-level 999}}}

   {:tid "t14"
    :exercises "A batch patch claiming a nonconformance rate above 100%. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:nonconformance-rate-percent 480.0}}}

   {:tid "t15"
    :exercises "A patch trying to self-issue an FDA 510(k) clearance / CE conformity mark. Authority reserved to the accredited regulatory body, never this actor -- permanent. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:issue-clearance? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t16"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:device-class :class-ii}}}

   {:tid "t17"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it. HARD hold."
    :request {:op :actuate-sterilizer :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario
  through `langgraph.graph/run*` exactly as `medinstrmfg.sim` does.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- num-cell [v]
  (if (nil? v) "—" (str "<span class=\"num\">" (esc v) "</span>")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- yes-no [ok?]
  (if ok? "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact the run actually put on the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "medinstrmfg.operation/build")
               ". Note that " (code ":approval-granted") " is emitted to the graph's in-memory "
               (code ":audit") " channel only — " (code "medinstrmfg.operation") " never appends it "
               "to the store ledger, so it is not a fact this page counts. An approved request is "
               "visible as the " (code ":committed") " fact it produced.")
          (table ["Measure" "Count"]
                 [(tr "requests driven" (num-cell (count runs)))
                  (tr "ledger facts" (num-cell (count led)))
                  (tr "commits" (num-cell (n :committed)))
                  (tr "governor HARD holds" (num-cell (n :governor-hold)))
                  (tr "human rejections on the ledger" (num-cell (n :approval-rejected)))
                  (tr "human approvals handed back to the graph"
                      (num-cell (count (filter #(= :approved (:human %)) runs))))]))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"err\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"err\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"err\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one " (code "langgraph.graph/run*") " over the compiled actor. The "
             "governor column is the verdict map the governor itself returned; the human column "
             "is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ". The right-hand column is the only prose here — it "
             "states what the request probes, never what happened.")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is one violation inside a " (code ":governor-hold") " fact on the "
               "append-only ledger. The rule name and the detail text are the governor's own "
               (code ":violations") " entries — this page holds no rule text of its own. A HARD "
               "hold is never overridable and never reaches a human.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"err\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (num-cell (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (num-cell (:confidence r)))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "medinstrmfg.phase/phases") ". A governor HOLD always stays a "
               "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
               "the governor is clean. " (code ":schedule-maintenance") " is deliberately absent "
               "from every phase's " (code ":auto") " set — a permanent structural fact, not a "
               "rollout milestone still to come.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"err\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "medinstrmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "medinstrmfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid device classes" (kw-codes registry/valid-device-classes))
                (tr "sterility-assurance level (10^-n exponent)"
                    (str (code registry/sterility-assurance-level-min) " … "
                         (code registry/sterility-assurance-level-max)))
                (tr "nonconformance rate (%)"
                    (str (code registry/nonconformance-rate-min-percent) " … "
                         (code registry/nonconformance-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"err\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"err\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining
  "Headroom the same way `medinstrmfg.registry` recomputes it: the
  batch's own recorded quantity minus its own cumulative-shipped
  ground truth, with the same 0.0 default the registry applies."
  [b]
  (let [q (:quantity-units b) s (:shipped-units b 0.0)]
    (when (and (number? q) (number? s)) (- (double q) (double s)))))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "medinstrmfg.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists only because the " (code "t01") " intake op committed it. A field the "
               "record does not carry shows as —; " (code "batch-004") " has no "
               (code ":shipped-units") " of its own yet, so <em>Remaining</em> uses the same "
               (code "0.0") " default the registry itself applies when it recomputes headroom.")
          (table ["Batch" "Device class" "Lot" "SAL (10^-n)" "Quantity (units)"
                  "Shipped (units)" "Remaining" "Nonconformance (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:device-class b)) (fmt (:lot-number b))
                       (num-cell (:sterility-assurance-level b))
                       (num-cell (:quantity-units b))
                       (num-cell (:shipped-units b)) (num-cell (remaining b))
                       (num-cell (:nonconformance-rate-percent b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (yes-no (registry/batch-ready? b))
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Machining / molding / sterilization equipment"
        (str "Read back from " (code "medinstrmfg.store/all-equipment") ". Equipment ids are never "
             "a request " (code ":subject") " in this domain (a maintenance draft id is), so no "
             "ledger-status column is shown for them — " (code ":last-scheduled-maintenance-date")
             " is the field the commit path actually writes onto an equipment record. Nothing on "
             "this page ever actuates any of these units.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (yes-no (registry/equipment-ready? e))
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (num-cell (count (filter #(= (:id e) (:equipment-id %))
                                              (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "medinstrmfg.store/all-maintenance") ". The "
               "maintenance number is minted by "
               (code "medinstrmfg.registry/register-maintenance") " at commit time. A draft is a "
               "record a plant coordinator keeps — it actuates no equipment.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-equipment?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-equipment? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "medinstrmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Units" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (num-cell (:units s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "medinstrmfg.store/safety-concerns")
               "). A concern may be raised against ANY equipment, verified or not — "
               (code "sterilizer-002") " below is neither inspected nor on file, and the concern "
               "still lands. Safety-relevant reporting is never blocked on an administrative "
               "technicality; it is instead always escalated to a human.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "medinstrmfg.store/ledger")
             " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (num-cell (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "err"
                                  :approval-rejected "err"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-3250 (medinstrmfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<div class=\"bar\">"
       "<span class=\"badge\">ISIC 3250</span>"
       "<span class=\"badge\">medinstrmfg</span>"
       "<span class=\"badge\">read-only sample</span>"
       "</div>\n"
       "<h1>Medical &amp; dental instrument plant operations — operator console</h1>\n"
       "<p class=\"subtitle\">Governor "
       (code "medical-instrument-plant-operations-governor")
       " · actor " (code (:actor-id coordinator))
       " · role " (code (:actor-role coordinator))
       " · phase " (code (:phase coordinator))
       "</p>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n<footer>"
       "Generated at build time by <code>medinstrmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>medinstrmfg.operation</code> actor graph over the real "
       "<code>medinstrmfg.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "This actor never controls machining, molding or sterilization equipment, and never issues "
       "an FDA 510(k) clearance or a CE conformity mark. No usage, revenue or performance metric "
       "is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is
    ;; not evidence of a governor. Do not weaken this.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
