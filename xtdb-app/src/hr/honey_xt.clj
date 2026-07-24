(ns hr.honey-xt
  "HoneySQL extensions for XTDB's bitemporal SQL — the entire vendor
  surface the app needs, registered through HoneySQL's public API and
  wrapped in helpers in the style of honey.sql.helpers:

    (-> (erase-from :employees) (h/where [...]))   ERASE FROM ...
    (-> (h/update :employees)
        (for-valid-time-from date) ...)            UPDATE ... FOR VALID_TIME FROM ?
    (h/from [(for-all-valid-time :employees)])     ... FOR ALL VALID_TIME
    (for-valid-time-as-of :employees date)         ... FOR VALID_TIME AS OF ?
    (for-system-time-as-of table-expr ts)          ... FOR SYSTEM_TIME AS OF ?
                                                   (composable with the above)

  Note the formatters emit VALID_TIME/SYSTEM_TIME literally — HoneySQL's
  sql-kw would render clause-keyword hyphens as spaces. Loading this
  namespace performs the registrations (side effects)."
  (:require [honey.sql :as sql]))

(defn- expr
  "Format v as an expression, lifting bare (non-DSL) values like
  LocalDate/OffsetDateTime into parameters."
  [v]
  (sql/format-expr (if (or (vector? v) (keyword? v)) v [:lift v])))

;; ERASE FROM — XT's across-all-time deletion; DELETE's grammar, so
;; borrow :delete-from's formatter (the emitted keyword comes from the
;; clause name itself).
(sql/register-clause! :erase-from :delete-from :delete-from)

;; UPDATE ... FOR VALID_TIME FROM <ts> SET ... — temporal DML: the
;; period of validity this update applies to.
(sql/register-clause! :for-valid-time-from
  (fn [_ v]
    (let [[s & params] (expr v)]
      (into [(str "FOR VALID_TIME FROM " s)] params)))
  :set)

;; Table qualifiers for :from position (triple-nest: outer = table
;; list, middle = [table-expr] pair slot, inner = the call).
(sql/register-fn! :for-all-valid-time
  (fn [_ [table]]
    (let [[s & params] (expr table)]
      (into [(str s " FOR ALL VALID_TIME")] params))))

(sql/register-fn! :for-valid-time-as-of
  (fn [_ [table t]]
    (let [[ts & tp] (expr table)
          [vs & vp] (expr t)]
      (into [(str ts " FOR VALID_TIME AS OF " vs)] (concat tp vp)))))

(sql/register-fn! :for-system-time-as-of
  (fn [_ [table t]]
    (let [[ts & tp] (expr table)
          [vs & vp] (expr t)]
      (into [(str ts " FOR SYSTEM_TIME AS OF " vs)] (concat tp vp)))))

;; ---------- helpers (honey.sql.helpers style) ----------

(defn- clause-helper
  "Mirror honey.sql.helpers' convention: when the first argument is a
  map it is the query being threaded."
  [k args]
  (if (map? (first args))
    (assoc (first args) k (second args))
    {k (first args)}))

(defn erase-from
  "Like honey.sql.helpers/delete-from, but across all of time."
  [& args]
  (clause-helper :erase-from args))

(defn for-valid-time-from
  "Temporal DML: the validity period this UPDATE applies to."
  [& args]
  (clause-helper :for-valid-time-from args))

;; Expression constructors for table qualifiers, used inside (h/from …).
(defn for-all-valid-time [table]
  [:for-all-valid-time table])

(defn for-valid-time-as-of [table t]
  [:for-valid-time-as-of table t])

(defn for-system-time-as-of [table-expr t]
  [:for-system-time-as-of table-expr t])
