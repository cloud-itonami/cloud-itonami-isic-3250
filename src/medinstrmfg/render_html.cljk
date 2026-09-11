(ns medinstrmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator at all. This namespace drives the REAL
  actor stack -- `medinstrmfg.operation` (a langgraph-clj StateGraph)
  -> `medinstrmfg.advisor` -> `medinstrmfg.governor` ->
  `medinstrmfg.phase` -> `medinstrmfg.store` -- through a scenario
  adapted from this repo's own `medinstrmfg.sim` demo driver
  (`clojure -M:dev:run`), and renders the page from whatever that run
  actually produced.

  NOTHING on the page is hand-typed telemetry:

    - every batch/equipment/maintenance/shipment/safety-concern row is
      read back out of the `medinstrmfg.store` SSoT AFTER the run;
    - every disposition, hold rule and hold `:detail` string comes from
      `medinstrmfg.governor`'s own output for that run;
    - the action-gate table is projected from `medinstrmfg.phase/phases`
      and `medinstrmfg.governor/allowed-ops` themselves, so it can never
      drift from the code it documents;
    - the run log is derived from each `langgraph.graph/run*` result's
      own `:status` / `:disposition` / `:audit`.

  The scenario input (which ops, against which seeded ids) is authored
  here -- that is what a scenario IS -- but every value rendered from it
  is the actor's answer, not a literal.

  DETERMINISM: there is no clock and no random id anywhere in this
  actor's commit path (`medinstrmfg.registry` numbers records off the
  store's own monotonic sequence, and no ledger fact carries a
  timestamp), so no epoch-ms constant needs threading in from the
  caller; two runs from the same seed are byte-identical. The page
  therefore carries no generation timestamp either -- deliberately.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [medinstrmfg.governor :as governor]
            [medinstrmfg.operation :as op]
            [medinstrmfg.phase :as phase]
            [medinstrmfg.store :as store]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

;; ----------------------------- the real run -----------------------------

(defn run-demo!
  "Runs a freshly seeded store through a scenario that reaches every
  disposition this actor can produce, and returns
  `{:db <store> :runs [<run-result-record> ...]}`.

  Clean / human-approved paths (the operator console must show that
  governed does not mean frozen):

    - `:log-production-batch` on the verified+registered `batch-001`
      with a clean patch -- the ONE op in phase 3's `:auto` set, so it
      auto-commits with no human in the loop;
    - `:schedule-maintenance` `mnt-1` against the verified+registered
      `machining-001` -- governor-clean, but `:schedule-maintenance` is
      deliberately absent from EVERY phase's `:auto` set
      (`medinstrmfg.phase`), so it escalates and a human approves;
    - `:flag-safety-concern` `concern-1` -- always
      `:coordination/safety-concern` stakes, so the governor escalates
      regardless of confidence; a human approves;
    - `:coordinate-shipment` `ship-1` for 500 units of `batch-001`
      (1000 already shipped of 5000 produced) -- escalates, approved;
    - `:coordinate-shipment` `ship-4` for 100 units of `batch-001` --
      escalates and the human REJECTS it, so the console also shows a
      soft hold that a human, not the governor, produced.

  HARD holds -- every one of these is a governor verdict that NEVER
  reaches a human and can never be approved (one request per rule, the
  'exercise the failure mode directly' discipline this fleet's sim
  drivers use):

    - a caller whose own request `:effect` is not `:propose`;
    - an op outside the closed allowlist;
    - maintenance against the UNVERIFIED/unregistered `sterilizer-002`;
    - a shipment against the UNVERIFIED/unregistered `batch-003`;
    - a shipment whose 100 units would blow through `batch-002`'s own
      logged production quantity (750 already shipped of 800);
    - a maintenance proposal that tries to ACTUATE the machining centre
      directly (permanent scope boundary);
    - a double-schedule of `mnt-1`;
    - a batch patch with a fabricated device class;
    - a batch patch with an implausible sterility-assurance level;
    - a batch patch with an implausible nonconformance rate;
    - a patch trying to self-issue an FDA 510(k)/CE-mark clearance."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (atom [])
        record! (fn [r] (swap! runs conj r) r)
        exec! (fn [tid request]
                (record! (g/run* actor {:request request :context coordinator}
                                 {:thread-id tid})))
        resolve! (fn [tid status]
                   (record! (g/run* actor {:approval {:status status :by "coord-1"}}
                                    {:thread-id tid :resume? true})))]

    ;; --- clean / approved -------------------------------------------------
    (exec! "t1-batch-intake"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:device-class :class-ii :last-assessed "2026-07-14"}})

    (exec! "t2-maintenance"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "machining-001" :maintenance-type :tool-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (resolve! "t2-maintenance" :approved)

    (exec! "t3-safety"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "machining-001" :severity :moderate
                    :description "精密加工公差の逸脱兆候、滅菌バリデーション再確認要"}})
    (resolve! "t3-safety" :approved)

    (exec! "t4-shipment"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 500.0
                    :destination "buyer-hospital-north"}})
    (resolve! "t4-shipment" :approved)

    ;; --- escalated, then REJECTED by the human ----------------------------
    (exec! "t5-shipment-rejected"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :units 100.0
                    :destination "buyer-clinic-west"}})
    (resolve! "t5-shipment-rejected" :rejected)

    ;; --- HARD holds (never reach a human) ---------------------------------
    (exec! "h1-not-propose"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:device-class :class-ii}})

    (exec! "h2-unknown-op"
           {:op :actuate-sterilizer :effect :propose :subject "batch-001"})

    (exec! "h3-equipment-unverified"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "sterilizer-002" :maintenance-type :cycle-validation
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! "h4-batch-unverified"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-hospital-south"}})

    (exec! "h5-quantity-exceeded"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-hospital-east"}})

    (exec! "h6-actuate-blocked"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "machining-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! "h7-double-schedule"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "machining-001" :maintenance-type :tool-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! "h8-device-class"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:device-class :class-ix}})

    (exec! "h9-sterility-level"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:sterility-assurance-level 999}})

    (exec! "h10-nonconformance"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:nonconformance-rate-percent 999.0}})

    (exec! "h11-clearance-authority"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-clearance? true}})

    {:db db :runs @runs}))

;; ----------------------------- small helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- label
  "Keyword -> its full name WITHOUT the leading colon, anything else ->
  its printed form. Used for the mixed keyword/string values the actor
  cites (`:cites` is a vector of patch keys for a batch intake, but of
  equipment/batch ids -- plain strings -- for maintenance and shipment
  proposals).

  The namespace is deliberately kept: `clojure.core/name` would render
  `:batch/upsert` as `upsert` and `:coordination/safety-concern` as
  `safety-concern`, which are not the values the governor's closed
  allowlists actually contain -- a page that showed those would be
  quietly lying about the contract it claims to document."
  [v]
  (cond (nil? v) ""
        (keyword? v) (subs (str v) 1)
        :else (str v)))

(defn- labels [xs] (str/join ", " (map label xs)))

(defn- qty
  "Render a stored double without a spurious trailing `.0` for whole
  units, so `5000.0` reads as `5000` while `0.8` stays `0.8`. Pure
  formatting of a real stored value -- no rounding of the value itself."
  [x]
  (cond
    (nil? x) "—"
    (and (number? x) (== (double x) (Math/floor (double x)))) (str (long x))
    :else (str x)))

(defn- yn [b]
  (if (true? b)
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- code [v] (str "<code>" (esc (label v)) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table
  "A plain semantic `<table>` -- no wrapper, no invented class. The
  design system's own skin (`jp-go-dds.skin`) block-ifies and
  overflow-scrolls `table:not(.dads-table__table)` itself, so wrapping
  it would only add markup that nothing styles."
  [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

;; ----------------------------- run-log projection -----------------------------

(defn- audit-of [r] (get-in r [:state :audit] []))

(defn- facts-of [r t] (filter #(= t (:t %)) (audit-of r)))

(defn- run-summary
  "Projects ONE `langgraph.graph/run*` result into the row the console
  shows. Everything here is read out of the result -- the op/subject
  come from the graph's own checkpointed `:request` channel (so the
  resumed halves of an approval are labelled by the actor, not by us),
  the disposition from the `:disposition` channel, the reasons from the
  audit facts the governor and phase gate wrote."
  [r]
  (let [request (get-in r [:state :request])
        disposition (get-in r [:state :disposition])
        status (:status r)
        hold (last (facts-of r :governor-hold))
        rejected (last (facts-of r :approval-rejected))
        requested (last (facts-of r :approval-requested))
        granted (last (facts-of r :approval-granted))
        committed (last (facts-of r :committed))]
    {:op (:op request)
     :subject (:subject request)
     :status status
     :disposition disposition
     :kind (cond hold :hard-hold
                 rejected :human-rejected
                 (and committed granted) :approved
                 committed :auto-committed
                 requested :awaiting-approval
                 :else :other)
     :rules (cond hold (:basis hold)
                  rejected (:basis rejected)
                  :else nil)
     :phase-reason (:phase-reason hold)
     :escalation-reason (:reason requested)
     :approved-by (:by granted)
     :summary (:summary committed)
     :confidence (or (:confidence hold) (:confidence requested))}))

(defn- kind-cell [{:keys [kind rules phase-reason escalation-reason approved-by]}]
  (case kind
    :hard-hold (str "<span class=\"critical\">HARD hold</span> · <code>"
                    (esc (labels rules)) "</code>"
                    (when phase-reason (str " · <code>" (esc (label phase-reason)) "</code>")))
    :human-rejected (str "<span class=\"warn\">rejected by human</span> · <code>"
                         (esc (labels rules)) "</code>")
    :approved (str "<span class=\"ok\">approved &amp; committed</span> · by "
                   (esc approved-by))
    :auto-committed "<span class=\"ok\">auto-committed</span>"
    :awaiting-approval (str "<span class=\"warn\">awaiting human approval</span> · <code>"
                            (esc (label escalation-reason)) "</code>")
    "<span class=\"muted\">in progress</span>"))

(defn- run-row [i s]
  (row (str i)
       (code (:op s))
       (esc (:subject s))
       (kind-cell s)
       (str "<span class=\"muted\">" (esc (label (:status s))) " / "
            (esc (label (:disposition s))) "</span>")))

;; ----------------------------- gate projection -----------------------------

(defn- gate-rows
  "Projects the action gate straight out of `medinstrmfg.phase/phases`
  and `medinstrmfg.governor/allowed-ops`, so this table is the code,
  not a description of it. If someone adds an op to a phase's `:auto`
  set, this page changes on the next build."
  []
  (let [ph phase/default-phase
        {:keys [writes auto]} (get phase/phases ph)]
    (for [op (sort-by name governor/allowed-ops)]
      (row (code op)
           (cond
             (not (contains? writes op))
             (str "<span class=\"critical\">disabled at phase " ph " → HOLD</span>")
             (contains? auto op)
             "<span class=\"ok\">auto-commit when governor-clean</span>"
             :else
             "<span class=\"warn\">human approval required at every phase</span>")
           (yn (contains? writes op))
           (yn (contains? auto op))))))

(defn- hold-rule-rows
  "Distinct governor hold rules this run actually produced, with the
  governor's OWN `:detail` string for each. Nothing is paraphrased."
  [ledger]
  (let [violations (->> ledger
                        (filter #(= :governor-hold (:t %)))
                        (mapcat :violations))
        by-rule (group-by :rule violations)]
    (for [rule (sort-by name (keys by-rule))]
      (let [vs (get by-rule rule)]
        (row (code rule)
             (str "<span class=\"num\">" (count vs) "</span>")
             (esc (:detail (first vs))))))))

;; ----------------------------- store projections -----------------------------

(defn- batch-row [{:keys [id device-class lot-number sterility-assurance-level
                          quantity-units nonconformance-rate-percent shipped-units
                          verified? registered? last-assessed]}]
  (row (esc id) (code device-class) (esc lot-number)
       (str "10<sup>-" (esc sterility-assurance-level) "</sup>")
       (str "<span class=\"num\">" (qty quantity-units) "</span>")
       (str "<span class=\"num\">" (qty shipped-units) "</span>")
       (str "<span class=\"num\">" (qty nonconformance-rate-percent) "%</span>")
       (yn verified?) (yn registered?) (esc last-assessed)))

(defn- equipment-row [{:keys [id kind verified? registered?
                              last-maintenance-date last-scheduled-maintenance-date]}]
  (row (esc id) (code kind) (yn verified?) (yn registered?)
       (esc (or last-maintenance-date "—"))
       (esc (or last-scheduled-maintenance-date "—"))))

(defn- approvers
  "subject -> the human who approved it, read out of the runs' own
  `:approval-granted` audit facts.

  NOTE (real defect in this repo, not a rendering choice): the
  approver's name never reaches the SSoT. `medinstrmfg.operation`'s
  `:request-approval` node puts `:approved-by` on the record's
  `:payload` key, but `medinstrmfg.store/commit-record!` destructures
  `:value` and ignores `:payload`, so the stored maintenance/shipment/
  concern entities carry no approver at all. The audit facts do, so
  the console joins them back on `:subject` rather than showing a
  column that is structurally always empty."
  [runs]
  (into {} (for [r runs
                 f (facts-of r :approval-granted)]
             [(:subject f) (:by f)])))

(defn- maintenance-row [by {:keys [id equipment-id maintenance-type scheduled-date
                                   maintenance-number scheduled?]}]
  (row (esc id) (esc (or maintenance-number "—")) (esc equipment-id)
       (code maintenance-type) (esc scheduled-date)
       (yn scheduled?) (esc (get by id "—"))))

(defn- shipment-row [db by draft]
  (let [sid (get draft "shipment_id")
        {:keys [batch-id units destination]} (store/shipment db sid)]
    (row (esc (get draft "record_id")) (esc sid)
         (esc batch-id)
         (str "<span class=\"num\">" (qty units) "</span>")
         (esc destination) (esc (get by sid "—")))))

(defn- concern-row [by {:keys [id equipment-id severity description]}]
  (row (esc id) (esc (or equipment-id "—")) (code severity)
       (esc description) (esc (get by id "—"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (row (code t) (code op) (esc subject) (code disposition) (code (labels basis))))

;; ----------------------------- page -----------------------------

(defn render
  "Renders the whole `operator-console.html` document from the result of
  `run-demo!` (or any other real run of this actor)."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        summaries (map run-summary runs)
        by (approvers runs)
        n-of (fn [k] (count (filter #(= k (:kind %)) summaries)))
        holds (count (filter #(= :governor-hold (:t %)) ledger))
        commits (count (filter #(= :committed (:t %)) ledger))
        ph phase/default-phase]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>cloud-itonami-isic-3250 · medical &amp; dental instrument plant operations — Operator Console</title>\n"
     "<style>" (skin/dds+skin) "</style>\n"
     "</head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of medical and dental instruments and supplies (ISIC 3250) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">phase " ph " · " (esc (:label (get phase/phases ph))) "</span> "
     "<span class=\"badge\">maintenance &amp; shipment always human-approved</span></p>\n"
     "<p class=\"muted\">Generated at build time by <code>medinstrmfg.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real run of the "
     "<code>medinstrmfg.operation</code> StateGraph — advisor → governor → phase gate → store. "
     "Every figure below is actor output, not sample text. No timestamps: the page is "
     "byte-identical on every rebuild from the same seed.</p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">"
     (n-of :auto-committed) " auto-committed · "
     (n-of :approved) " human-approved · "
     (n-of :human-rejected) " rejected by a human · "
     "<strong>" (n-of :hard-hold) " HARD governor holds</strong> (never offered to a human) · "
     commits " committed facts and " holds " hold facts on the append-only ledger."
     "</p>\n"
     (table ["#" "Op" "Subject" "Outcome" "graph status / disposition"]
            (map-indexed (fn [i s] (run-row (inc i) s)) summaries))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor holds this run</h2>\n"
     "    <p class=\"muted\">Distinct HARD rules the Medical Instrument Plant Operations Governor "
     "fired, with the governor's own reason text. A HARD hold is not an escalation: it never "
     "reaches a human and no approval can release it.</p>\n"
     (table ["Rule" "Count" "Governor's reason"] (hold-rule-rows ledger))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate</h2>\n"
     "    <p class=\"muted\">Projected from <code>medinstrmfg.phase/phases</code> and "
     "<code>medinstrmfg.governor/allowed-ops</code> at build time. Confidence floor "
     "<span class=\"num\">" governor/confidence-floor "</span>; any proposal whose stake is in "
     "<code>" (esc (labels (sort-by label governor/high-stakes))) "</code> always needs a human. "
     "Proposal effects are limited to the closed set "
     "<code>" (esc (labels (sort-by label governor/allowed-proposal-effects))) "</code> — anything "
     "outside it is direct machining/moulding/sterilisation equipment control, which this actor "
     "never performs.</p>\n"
     (table ["Op" "Gate at this phase" "May write?" "May auto-commit?"] (gate-rows))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">SSoT after the run. <code>verified?</code> and <code>registered?</code> "
     "are the ground truth the governor independently re-derives before any shipment is allowed — "
     "never the advisor's own report.</p>\n"
     (table ["Batch" "Device class" "Lot" "SAL" "Produced (units)" "Shipped (units)"
             "Nonconformance" "Verified?" "Registered?" "Last assessed"]
            (map batch-row (store/all-batches db)))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Machining / moulding / sterilisation equipment</h2>\n"
     (table ["Unit" "Kind" "Verified?" "Registered?" "Last maintenance" "Next scheduled"]
            (map equipment-row (store/all-equipment db)))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Maintenance windows (drafts)</h2>\n"
     "    <p class=\"muted\">Drafted by <code>medinstrmfg.registry/register-maintenance</code>. "
     "A draft is a record a plant coordinator keeps — this actor never actuates the equipment.</p>\n"
     (table ["Maintenance" "Record no." "Equipment" "Type" "Scheduled" "Scheduled?" "Approved by"]
            (map (partial maintenance-row by) (store/all-maintenance db)))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Shipment coordination (drafts)</h2>\n"
     "    <p class=\"muted\">Drafted by <code>medinstrmfg.registry/register-shipment</code>. "
     "No freight carrier is dispatched. Quantities were independently recomputed against the "
     "batch's own logged production quantity before commit.</p>\n"
     (table ["Record no." "Shipment" "Batch" "Units" "Destination" "Approved by"]
            (map (partial shipment-row db by) (store/shipment-history db)))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Always high-stakes: a safety concern is never auto-committed at any "
     "phase, and is never blocked on the referenced equipment being verified.</p>\n"
     (table ["Concern" "Equipment" "Severity" "Description" "Approved by"]
            (map (partial concern-row by) (store/safety-concerns db)))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (append-only)</h2>\n"
     "    <p class=\"muted\">Every commit and every hold this run wrote. Escalation requests are "
     "deliberately absent: only the <code>:commit</code> and <code>:hold</code> nodes of the "
     "StateGraph may touch the ledger.</p>\n"
     (table ["Fact" "Op" "Subject" "Disposition" "Basis"] (map ledger-row ledger))
     "  </section>\n"
     "</main>\n"
     "<footer><p>cloud-itonami-isic-3250 · governed open occupation blueprint · "
     "generated from a real actor run, no hand-written figures.</p></footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (count (filter #(= :governor-hold (:t %)) ledger))]
    ;; Build-time invariant, not a convention: this console exists to show
    ;; that the governor actually refuses things. A scenario that produced
    ;; no HARD hold would render a page that quietly misrepresents the
    ;; actor, so refuse to write it at all.
    (when (zero? holds)
      (throw (ex-info (str "refusing to write " out
                           ": the run produced ZERO :governor-hold ledger entries. "
                           "The operator console must demonstrate at least one HARD "
                           "governor hold -- fix the scenario in run-demo!, do not "
                           "relax this check.")
                      {:out out :ledger-facts (count ledger) :governor-holds 0})))
    (let [html (render result)]
      (.mkdirs (.getParentFile (java.io.File. ^String out)))
      (spit out html :encoding "UTF-8")
      (println "wrote" out
               (str "(" (count ledger) " ledger facts, "
                    (count (filter #(= :committed (:t %)) ledger)) " committed, "
                    holds " governor holds, "
                    (count (store/maintenance-history db)) " maintenance drafts, "
                    (count (store/shipment-history db)) " shipment drafts, "
                    (count (store/safety-concerns db)) " safety concerns)")))))
