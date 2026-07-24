(ns hr.db
  "Datomic Pro implementation: immutable facts, peer model, tx-time.

  The disciplines from fact-based modeling show up literally in the
  schema (see resources/migrations/): a system-owned UUID as
  :db.unique/identity with email as :db.unique/value (the
  mutable-identifier trap), reviews as :db/isComponent children,
  :db/noHistory on the churny last-login, and salary changes reified as
  event entities — because Datomic's native history knows only ONE
  timeline (transaction time), the valid/effective axis has to live in
  the domain model.

  This namespace is stateless: every function takes the connection as
  its first argument. Lifecycle (connect/migrate/seed/release) lives in
  hr.system."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d])
  (:import [java.util UUID Date]))

(def info
  {:db "Datomic Pro 1.0.7622"
   :personality "immutable facts · peer model · tx-time history · Datalog"
   :caps {:valid-axis "derived from reified salary-change events (no native valid time)"
          :known-axis "native — d/as-of over transaction time (unforgeable; starts at first import)"
          :purge "retraction keeps history; true deletion = excision (index rewrite)"
          :search "built-in Lucene fulltext (deprecated upstream, absent in Cloud)"
          :migrations "ragtime (custom DataStore over datoms); old attributes are permanent ghosts, history keeps old shapes"}})

(defn- today [] (str (java.time.LocalDate/now)))

;; ---------- seed ----------

(defn- employee-tx [{:keys [name email hired dept role salary-history reviews]}]
  ;; a string tempid, not a lookup ref: lookup refs resolve against the
  ;; PRE-transaction db and cannot see entities created in the same tx
  (let [eid (UUID/randomUUID)
        tmp (str "emp-" eid)]
    (concat
     [{:db/id tmp
       :employee/id eid :employee/name name :employee/email email
       :employee/hired hired :employee/active true
       :employee/reviews (vec (for [{:keys [date text]} reviews]
                                {:review/date date :review/text text}))}
      {:assignment/employee tmp :assignment/dept dept
       :assignment/role role :assignment/effective hired}]
     (for [{:keys [amount effective recorded]} salary-history]
       {:salary-change/employee tmp :salary-change/amount amount
        :salary-change/effective effective :salary-change/recorded recorded}))))

(def ^:private batch-size 256)      ;employees per tx (~6k datoms — well under the cap)
(def ^:private pipeline-depth 8)    ;transactions kept in flight at once

(defn seed! [conn]
  ;; Bulk-load discipline (Datomic caps transaction size — see the naive
  ;; version's :db.error/transaction-timeout): STREAM the seed (never
  ;; slurp), pack employees into transactor-sized batches, and keep a
  ;; bounded window of them in flight so peer→transactor round-trips
  ;; overlap. This is the "partition, chunk, drip-feed" the docs prescribe.
  (with-open [r (io/reader (io/file "../seed.edn"))]
    (doseq [window (->> (line-seq r)
                        (map edn/read-string)
                        (partition-all batch-size)
                        (map (fn [chunk] (vec (mapcat employee-tx chunk))))
                        (partition-all pipeline-depth))]
      (run! deref (mapv #(d/transact conn %) window)))))

(defn empty-db? [conn]
  (empty? (d/q '[:find ?e :where [?e :employee/id]] (d/db conn))))

;; ---------- helpers ----------

(defn- latest-until [events valid]
  (->> events
       (filter #(<= (compare (:effective %) valid) 0))
       (sort-by (juxt :effective :recorded))
       last))

(defn- salary-events [db e]
  (->> (d/q '[:find ?amount ?eff ?rec ?note
              :in $ ?e
              :where [?s :salary-change/employee ?e]
                     [?s :salary-change/amount ?amount]
                     [?s :salary-change/effective ?eff]
                     [?s :salary-change/recorded ?rec]
                     [(get-else $ ?s :salary-change/note "") ?note]]
            db e)
       (map (fn [[a ef r n]] {:amount a :effective ef :recorded r :note n}))
       (sort-by (juxt :effective :recorded))))

(defn- assignments-of [db e]
  (->> (d/q '[:find ?dept ?role ?eff
              :in $ ?e
              :where [?a :assignment/employee ?e]
                     [?a :assignment/dept ?dept]
                     [?a :assignment/role ?role]
                     [?a :assignment/effective ?eff]]
            db e)
       (map (fn [[dp r ef]] {:dept dp :role r :effective ef}))
       (sort-by :effective)))

(defn- employee-row [db e]
  (let [m (d/pull db '[:employee/id :employee/name :employee/given-name
                       :employee/family-name :employee/email
                       :employee/hired :employee/active :employee/last-login] e)
        sal (latest-until (salary-events db e) (today))
        asg (last (assignments-of db e))]
    {:id (str (:employee/id m))
     :given-name (:employee/given-name m)
     :family-name (:employee/family-name m)
     ;; dual-shape fallback: d/as-of views from before the split-names
     ;; migration still carry the old single-name shape
     :name (if (:employee/given-name m)
             (str/trim (str (:employee/given-name m) " " (:employee/family-name m)))
             (:employee/name m))
     :email (:employee/email m)
     :hired (:employee/hired m)
     :active (:employee/active m)
     :last-login (some-> ^Date (:employee/last-login m) .toInstant str)
     :salary (:amount sal)
     :dept (:dept asg)
     :role (:role asg)}))

(defn- eid-of [db id-str]
  (d/q '[:find ?e . :in $ ?id :where [?e :employee/id ?id]]
       db (UUID/fromString id-str)))

(defn- latest-by-e
  "Reduce rows shaped [?e & _] to a map ?e→row, keeping per employee the
  row that sorts last by (order-fn row). This is the bulk replacement for
  the per-employee 'latest event' sub-query: ONE scan, grouped in memory."
  [rows order-fn]
  (reduce-kv (fn [m e rs] (assoc m e (last (sort-by order-fn rs))))
             {} (group-by first rows)))

;; ---------- public api ----------

(defn list-employees
  "One PAGE of the active roster, ordered by family name. At 50k the old
  'load every employee, build a detail row for each, then sort' shape is a
  non-starter — it fans an N+1 across the whole population and returns 50k
  rows to a browser. A browse view wants a page: scan the (e, family-name)
  pairs once, sort, take the window, and build detail rows ONLY for that
  window (so the per-row sub-queries are bounded by page size, not 50k)."
  [conn {:keys [limit offset] :or {limit 50 offset 0}}]
  (let [db (d/db conn)]
    (->> (d/q '[:find ?e ?fam
                :where [?e :employee/active true]
                       [?e :employee/family-name ?fam]] db)
         (sort-by (fn [[_ fam]] fam))
         (drop offset)
         (take limit)
         (map (fn [[e _]] (employee-row db e))))))

(defn get-employee [conn id-str]
  (let [db (d/db conn)
        e (eid-of db id-str)]
    (when e
      (assoc (employee-row db e)
             :salary-timeline (salary-events db e)
             :assignments (assignments-of db e)
             :reviews (->> (d/pull db '[{:employee/reviews [:review/date :review/text]}] e)
                           :employee/reviews
                           (map (fn [r] {:date (:review/date r) :text (:review/text r)}))
                           (sort-by :date))))))

(defn create-employee!
  "Writes the post-migration shape only — new employees never get an
  :employee/name datom (though the ghost attribute would happily
  accept one; nothing but discipline prevents it)."
  [conn {:keys [given-name family-name email hired dept role salary]}]
  (let [eid (UUID/randomUUID)
        tmp (str "emp-" eid)]
    @(d/transact conn
       [{:db/id tmp
         :employee/id eid
         :employee/given-name given-name :employee/family-name family-name
         :employee/email email :employee/hired hired :employee/active true}
        {:assignment/employee tmp :assignment/dept dept
         :assignment/role role :assignment/effective hired}
        {:salary-change/employee tmp :salary-change/amount salary
         :salary-change/effective hired :salary-change/recorded (today)}])
    "ok"))

(defn add-salary-change! [conn id-str {:keys [amount effective note]}]
  @(d/transact conn
     [{:salary-change/employee (eid-of (d/db conn) id-str)
       :salary-change/amount amount
       :salary-change/effective effective
       :salary-change/recorded (today)
       :salary-change/note (or note "")}]))

;; The engine can't re-date its single timeline, so a correction is
;; just another reified event — indistinguishable at the engine level,
;; distinguished only by our :salary-change/effective attribute.
(def correct-salary! add-salary-change!)

(defn assign! [conn id-str {:keys [dept role effective]}]
  @(d/transact conn
     [{:assignment/employee (eid-of (d/db conn) id-str)
       :assignment/dept dept :assignment/role role
       :assignment/effective effective}]))

(defn add-review! [conn id-str text]
  @(d/transact conn
     [{:db/id (eid-of (d/db conn) id-str)
       :employee/reviews [{:review/date (today) :review/text text}]}]))

(defn touch-login! [conn id-str]
  @(d/transact conn
     [{:db/id (eid-of (d/db conn) id-str) :employee/last-login (Date.)}]))

(defn purge!
  "Retraction alone would leave every salary and review in the history
  index forever. True forgetting is excision: the transactor rewrites
  its indexes. Heavy — and exactly why 'will I be legally forced to
  forget this?' is a schema-design question in Datomic."
  [conn id-str]
  (let [db (d/db conn)
        e (eid-of db id-str)
        related (concat
                 (d/q '[:find [?x ...] :in $ ?e :where [?x :salary-change/employee ?e]] db e)
                 (d/q '[:find [?x ...] :in $ ?e :where [?x :assignment/employee ?e]] db e)
                 (map :db/id (:employee/reviews (d/pull db '[{:employee/reviews [:db/id]}] e))))]
    (let [{:keys [db-after]} @(d/transact conn (vec (for [x (conj (vec related) e)]
                                                      {:db/excise x})))]
      ;; excision completes asynchronously with the next index job
      @(d/sync-excise conn (d/basis-t db-after)))))

(defn history-count
  "How many datoms has this entity EVER had? (Shows excision working.)"
  [conn id-str]
  (let [db (d/db conn)]
    (count (d/q '[:find ?e ?a ?v ?tx :in $ ?id
                  :where [?e :employee/id ?id]]
                (d/history db) (UUID/fromString id-str)))))

(defn search
  "Datomic Pro ships a Lucene fulltext index per attribute — but it is
  deprecated upstream and absent from Datomic Cloud. Paginated: the
  fulltext hit set is deduped to one row per employee and a page taken
  before the (bounded) per-row detail build."
  [conn q {:keys [limit offset] :or {limit 50 offset 0}}]
  (let [db (d/db conn)]
    (->> (d/q '[:find ?e ?text
                :in $ ?q
                :where [(fulltext $ :review/text ?q) [[?r ?text]]]
                       [?e :employee/reviews ?r]]
              db q)
         (reduce (fn [m [e text]] (cond-> m (not (contains? m e)) (assoc e text))) {})
         (sort-by key)
         (drop offset)
         (take limit)
         (map (fn [[e text]] (assoc (employee-row db e) :match text))))))

(defn asof-payroll
  "valid: derived from the reified events. known: NATIVE — d/as-of
  rewinds the whole database to what it contained at that instant
  (which, like XTDB's system time, cannot be backdated by imports)."
  [conn {:keys [valid known]}]
  (let [known-inst (when known
                     (if (str/includes? known "T")
                       (java.time.Instant/parse known)
                       (-> (java.time.LocalDate/parse known)
                           (.atStartOfDay (java.time.ZoneId/of "UTC"))
                           .toInstant)))
        db (cond-> (d/db conn)
             known-inst (d/as-of (Date/from known-inst)))
        ;; whole-population snapshot, but computed in bulk: one scan of the
        ;; salary events and one of the assignments (over the as-of db, so
        ;; the known axis is already applied), reduced to the latest event
        ;; effective on/before `valid` per employee — NOT a per-employee
        ;; sub-query fan-out.
        cur-sal  (-> (d/q '[:find ?e ?amount ?eff ?rec
                            :where [?s :salary-change/employee ?e]
                                   [?s :salary-change/amount ?amount]
                                   [?s :salary-change/effective ?eff]
                                   [?s :salary-change/recorded ?rec]] db)
                     (->> (filter (fn [[_ _ eff _]] (<= (compare eff valid) 0))))
                     (latest-by-e (fn [[_ _ eff rec]] [eff rec])))
        cur-dept (-> (d/q '[:find ?e ?dept ?eff
                            :where [?a :assignment/employee ?e]
                                   [?a :assignment/dept ?dept]
                                   [?a :assignment/effective ?eff]] db)
                     (->> (filter (fn [[_ _ eff]] (<= (compare eff valid) 0))))
                     (latest-by-e (fn [[_ _ eff]] eff)))
        names    (reduce (fn [m [e gn fn* nm]] (assoc m e {:g gn :f fn* :n nm}))
                         {} (d/q '[:find ?e ?gn ?fn ?nm
                                   :where [?e :employee/id]
                                          [(get-else $ ?e :employee/given-name "") ?gn]
                                          [(get-else $ ?e :employee/family-name "") ?fn]
                                          [(get-else $ ?e :employee/name "") ?nm]] db))]
    (->> cur-sal
         (keep (fn [[e row]]
                 (let [nm (names e)]
                   {:name (if (seq (:g nm))
                            (str/trim (str (:g nm) " " (:f nm)))
                            (:n nm))
                    :dept (some-> (cur-dept e) (nth 1))
                    :salary (nth row 1)})))
         (sort-by :name))))

(defn report [conn]
  ;; The peer still pulls the working set into local memory to aggregate
  ;; (that IS Datomic's analytical story), but it must do so in ONE scan
  ;; per relation, not the N+1 that a naive (group-by dept list-employees)
  ;; incurs: three d/q's — active ids, all salary events, all assignments
  ;; — then reduce to current-salary / current-dept per employee in memory.
  (let [db  (d/db conn)
        td  (today)
        act (d/q '[:find [?e ...] :where [?e :employee/active true]] db)
        cur-sal  (-> (d/q '[:find ?e ?amount ?eff ?rec
                            :where [?s :salary-change/employee ?e]
                                   [?s :salary-change/amount ?amount]
                                   [?s :salary-change/effective ?eff]
                                   [?s :salary-change/recorded ?rec]] db)
                     (->> (filter (fn [[_ _ eff _]] (<= (compare eff td) 0))))
                     (latest-by-e (fn [[_ _ eff rec]] [eff rec])))
        cur-dept (-> (d/q '[:find ?e ?dept ?eff
                            :where [?a :assignment/employee ?e]
                                   [?a :assignment/dept ?dept]
                                   [?a :assignment/effective ?eff]] db)
                     (latest-by-e (fn [[_ _ eff]] eff)))]
    (->> act
         (keep (fn [e] (when-let [dept (some-> (cur-dept e) (nth 1))]
                         {:dept dept :salary (some-> (cur-sal e) (nth 1))})))
         (group-by :dept)
         (map (fn [[dept rows]]
                (let [sals (keep :salary rows)]
                  {:dept dept
                   :headcount (count rows)
                   :avg-salary (long (/ (reduce + 0 sals) (max 1 (count sals))))})))
         (sort-by :dept))))
