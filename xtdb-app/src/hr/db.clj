(ns hr.db
  "XTDB 2 implementation: bitemporal SQL over the Postgres wire protocol.

  Note what is ABSENT here: no salary-event entities and no assignment
  entities. Salary, dept and role are plain columns on the employees
  table — the second time axis (valid time) IS the timeline, so the
  reified-event discipline that Datomic/Datalevin require simply
  dissolves.

  Most reads are HoneySQL (honey.sql.helpers' threaded style; the
  XT-specific temporal clauses come from hr.honey-xt). But `get-employee`
  showcases XTDB's OTHER query engine: native XTQL. XTQL is EDN data with
  `pull*`, so the whole detail view — employee + nested reviews + the
  valid-time history — comes back as one nested document from a single
  query, replacing four hand-stitched SQL reads. Two query languages, one
  pooled connection, one database: `xt/q` runs EDN XTQL over the very same
  pgwire datasource the SQL path uses.

  This namespace is stateless. The db handle is `{:ds <pooled pgwire
  datasource>}`: SQL functions and `get-employee`'s XTQL both go through
  `:ds`. Lifecycle (and the HikariCP pool) lives in hr.system."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [hr.honey-xt :as xt-sql]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [xtdb.api :as xt])
  (:import [java.time LocalDate]
           [java.util UUID]))

(def info
  {:db "XTDB 2.1.0"
   :personality "bitemporal · SQL + XTQL · schemaless records · columnar"
   :caps {:valid-axis "native — FOR VALID_TIME AS OF"
          :known-axis "native — FOR SYSTEM_TIME AS OF (unforgeable; starts at first import)"
          :purge "ERASE FROM — a designed-in primitive"
          :search "SQL LIKE scan (no text index)"
          :migrations "ragtime, ONE migration (nothing to create); rewrote all of valid time"}})

(defn- q [ds sqlmap]
  (jdbc/execute! ds (sql/format sqlmap)
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- x! [ds sqlmap]
  (jdbc/execute! ds (sql/format sqlmap)))

(defn- d [s] (LocalDate/parse s))

;; ---------- seed ----------

(def ^:private seed-batch 2000)     ;employees held/processed at once (memory bound)

(def ^:private emp-insert
  "INSERT INTO employees (_id, _valid_from, name, email, hired, active, salary, dept, role)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
(def ^:private rev-insert
  "INSERT INTO reviews (_id, employee_id, rdate, body) VALUES (?, ?, ?, ?)")

(defn seed! [ds]
  ;; Bulk load, XTDB-flavoured. Two hazards, both measured: a per-row
  ;; INSERT is ~4.5 ms/round-trip (~15 min for a large seed), and — the
  ;; XTDB-specific trap — a multi-row INSERT with thousands of BOUND
  ;; PARAMETERS is pathological (15 s for 3000 rows). The fast path is
  ;; JDBC batch execution: prepare ONE single-row INSERT and feed it many
  ;; param sets with execute-batch! (~60× faster). So: STREAM the seed
  ;; (never slurp), and batch it through prepared statements. The salary
  ;; timeline is built by inserting one record per period with an explicit
  ;; _valid_from — XTDB auto-closes each prior version at the next one's
  ;; valid time, so no per-row temporal UPDATEs are needed.
  (with-open [r   (io/reader (io/file "../seed.edn"))
              con (jdbc/get-connection ds)
              eps (jdbc/prepare con [emp-insert])
              rps (jdbc/prepare con [rev-insert])]
    (doseq [batch (->> (line-seq r) (map edn/read-string) (partition-all seed-batch))]
      (let [ided     (map #(assoc % ::id (str (UUID/randomUUID))) batch)
            emp-rows (for [{:keys [name email hired dept role salary-history] :as e} ided
                           {:keys [amount effective]} (sort-by :effective salary-history)]
                       [(::id e) (d effective) name email hired true amount dept role])
            rev-rows (for [{:keys [reviews] :as e} ided
                           {:keys [date text]} reviews]
                       [(str (UUID/randomUUID)) (::id e) date text])]
        (prepare/execute-batch! eps (vec emp-rows) {:batch-size 500})
        (when (seq rev-rows)
          (prepare/execute-batch! rps (vec rev-rows) {:batch-size 500}))))))

(defn empty-db? [ds]
  ;; XTDB is schemaless: a never-written table simply returns no rows
  ;; (verified — it does NOT throw), so no defensive catch is needed. A
  ;; real error (e.g. node unreachable) must surface, not be misread as
  ;; "empty" and trigger a duplicate seed (seeds use fresh UUIDs and XT
  ;; has no unique constraint, so a spurious seed doubles the dataset).
  (empty? (q ds (-> (h/select :_id) (h/from :employees) (h/limit 1)))))

;; ---------- public api ----------

(defn- row->employee [r]
  ;; no dual-shape handling: the migration rewrote ALL of valid time,
  ;; so every ordinary read sees the new shape — only deliberate
  ;; system-time archaeology can encounter the old one
  {:id (:_id r)
   :given-name (:given_name r) :family-name (:family_name r)
   :name (str/trim (str (:given_name r) " " (:family_name r)))
   :email (:email r) :hired (:hired r)
   :active (:active r) :salary (:salary r) :dept (:dept r) :role (:role r)
   :last-login (some-> (:last_login r) str)})

(defn list-employees
  "One PAGE of the active roster. Unlike Datomic/Datalevin — which must
  scan-and-sort the population in the peer/process — XTDB pushes ORDER BY
  + LIMIT + OFFSET into the engine, so only a page crosses the wire."
  [{:keys [ds]} {:keys [limit offset] :or {limit 50 offset 0}}]
  (->> (q ds (-> (h/select :*)
                 (h/from :employees)
                 (h/where [:= :active true])
                 (h/order-by :family_name :given_name)
                 (h/limit limit)
                 (h/offset offset)))
       (map row->employee)))

;; ---------- get-employee: native XTQL, not SQL ----------

(def ^:private employee-doc-q
  "One native XTQL query for the whole detail view. `pull*` nests the
  reviews and the valid-time history as sub-documents; the valid-time
  axis IS the salary/assignment timeline. `::id` is substituted with the
  target id before execution (xtql->sql over the pgwire client)."
  '(-> (from :employees [{:xt/id id}
                         given-name family-name email hired active salary dept role last-login])
       (where (= id ::id))
       (with {:reviews (pull* (fn [id]
                                (-> (from :reviews [{:employee-id id} rdate body])
                                    (order-by rdate))))
              :history (pull* (fn [id]
                                (-> (from :employees {:for-valid-time :all-time
                                                      :bind [{:xt/id id} salary dept role
                                                             xt/valid-from xt/system-from]})
                                    (order-by xt/valid-from))))})))

(defn- day
  "First 10 chars of a temporal value's string form — the yyyy-MM-dd."
  [v]
  (some-> v str (subs 0 10)))

(defn get-employee [{:keys [ds]} id]
  (when-let [r (first (xt/q ds (walk/postwalk-replace {::id id} employee-doc-q)))]
    (let [history (:history r)]
      {:id (:id r)
       :given-name (:given-name r) :family-name (:family-name r)
       :name (str/trim (str (:given-name r) " " (:family-name r)))
       :email (:email r) :hired (:hired r) :active (:active r)
       :salary (:salary r) :dept (:dept r) :role (:role r)
       :last-login (some-> (:last-login r) str)
       ;; both the salary timeline and the assignment history are just
       ;; views of the same valid-time versions (partition-by collapses
       ;; unchanged runs), so one pulled sub-document feeds both.
       :salary-timeline (->> history
                             (map (fn [h] {:amount (:salary h)
                                           :effective (day (:xt/valid-from h))
                                           :recorded (day (:xt/system-from h))
                                           :note ""}))
                             (partition-by :amount)
                             (map first))
       :assignments (->> history
                         (map (fn [h] {:dept (:dept h) :role (:role h)
                                       :effective (day (:xt/valid-from h))}))
                         (partition-by (juxt :dept :role))
                         (map first))
       :reviews (->> (:reviews r)
                     (map (fn [rv] {:date (:rdate rv) :text (:body rv)})))})))

;; ---------- writes & other reads (SQL) ----------

(defn create-employee! [{:keys [ds]} {:keys [given-name family-name email hired dept role salary]}]
  ;; schemaless = no unique constraint: email uniqueness is OUR problem
  (when (seq (q ds (-> (h/select :_id)
                       (h/from :employees)
                       (h/where [:= :email email]))))
    (throw (ex-info "email already taken (app-enforced: XTDB has no unique constraints)" {})))
  (let [id (str (UUID/randomUUID))]
    (x! ds (-> (h/insert-into :employees)
               (h/columns :_id :_valid_from :given_name :family_name :email :hired :active :salary :dept :role)
               (h/values [[id (d hired) given-name family-name email hired true salary dept role]])))
    id))

(defn add-salary-change! [{:keys [ds]} id {:keys [amount effective]}]
  ;; identical statement whether it's a raise from today, a scheduled
  ;; future raise, or a backdated correction — valid time handles all
  (x! ds (-> (h/update :employees)
             (xt-sql/for-valid-time-from (d effective))
             (h/set {:salary amount})
             (h/where [:= :_id id]))))

(def correct-salary! add-salary-change!)

(defn assign! [{:keys [ds]} id {:keys [dept role effective]}]
  (x! ds (-> (h/update :employees)
             (xt-sql/for-valid-time-from (d effective))
             (h/set {:dept dept :role role})
             (h/where [:= :_id id]))))

(defn add-review! [{:keys [ds]} id text]
  (x! ds (-> (h/insert-into :reviews)
             (h/columns :_id :employee_id :rdate :body)
             (h/values [[(str (UUID/randomUUID)) id (str (LocalDate/now)) text]]))))

(defn touch-login! [{:keys [ds]} id]
  ;; every touch writes a new immutable row version — the churn cost of
  ;; keeping ALL history (Datomic would mark this :db/noHistory)
  (x! ds (-> (h/update :employees)
             (h/set {:last_login :current-timestamp})
             (h/where [:= :_id id]))))

(defn purge!
  "GDPR was a design goal: ERASE removes the record across ALL of
  time, both axes, permanently."
  [{:keys [ds]} id]
  (x! ds (-> (xt-sql/erase-from :reviews) (h/where [:= :employee_id id])))
  (x! ds (-> (xt-sql/erase-from :employees) (h/where [:= :_id id]))))

(defn search
  "Employees with a review note matching q, one row each, with a sample
  match. XTDB has no text index, so this IS a LIKE scan of the review
  bodies — but done ONCE: a semi-join (_id IN the distinct employee_ids of
  the matching reviews) pages the employees, then a single grouped lookup
  fetches a sample matching body for just that page (index-backed on the
  reviews.employee_id top-level field). No per-hit SELECT fan-out, and no
  correlated subquery over the whole population."
  [{:keys [ds]} q* {:keys [limit offset] :or {limit 50 offset 0}}]
  (let [pat  (str "%" q* "%")
        page (q ds (-> (h/select :*)
                       (h/from :employees)
                       (h/where [:= :active true]
                                [:in :_id (-> (h/select-distinct :employee_id)
                                              (h/from :reviews)
                                              (h/where [:like :body pat]))])
                       (h/order-by :family_name :given_name)
                       (h/limit limit)
                       (h/offset offset)))
        ids  (mapv :_id page)
        match (when (seq ids)
                ;; one sample matching body per page employee — the page is
                ;; small (<= limit), so this is a bounded index lookup, and
                ;; we dedupe in Clojure (XTDB has no min/max over text).
                (->> (q ds (-> (h/select :employee_id :body)
                               (h/from :reviews)
                               (h/where [:in :employee_id ids] [:like :body pat])))
                     (reduce (fn [m {:keys [employee_id body]}]
                               (cond-> m (not (contains? m employee_id))
                                       (assoc employee_id body)))
                             {})))]
    (map (fn [r] (assoc (row->employee r) :match (get match (:_id r)))) page)))

(defn- ts
  "System-time param: accept a date or a full ISO timestamp."
  [s]
  (if (str/includes? s "T")
    (java.time.OffsetDateTime/parse s)
    (d s)))

(defn asof-payroll [{:keys [ds]} {:keys [valid known]}]
  ;; the dual-axis query: qualifiers compose around the table
  (let [as-of-valid (xt-sql/for-valid-time-as-of :employees (d valid))
        table (if known
                (xt-sql/for-system-time-as-of as-of-valid (ts known))
                as-of-valid)]
    (->> (q ds (-> (h/select :given_name :family_name :dept :salary)
                   (h/from [table])
                   (h/where [:= :active true])
                   (h/order-by :family_name :given_name)))
         (map (fn [r] {:name (str/trim (str (:given_name r) " " (:family_name r)))
                       :dept (:dept r) :salary (:salary r)})))))

(defn report [{:keys [ds]}]
  ;; the OLAP story: a stock SQL aggregate any BI tool could run
  (->> (q ds (-> (h/select :dept
                           [[:count :*] :headcount]
                           [[:avg :salary] :avg_salary])
                 (h/from :employees)
                 (h/where [:= :active true])
                 (h/group-by :dept)
                 (h/order-by :dept)))
       (map (fn [r] {:dept (:dept r)
                     :headcount (:headcount r)
                     :avg-salary (long (:avg_salary r))}))))
