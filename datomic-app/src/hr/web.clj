(ns hr.web
  "Routing + hiccup UI. Deliberately identical across the three apps —
  the db handle is opaque here and threaded into every hr.db call; all
  interesting divergence lives in hr.db."
  (:require [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hr.db :as db]
            [malli.core :as m]
            [malli.error :as me]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp]))

(def employee-schema
  [:map
   [:given-name [:string {:min 1}]]
   [:family-name [:string {:min 1}]]
   [:email [:re #".+@.+\..+"]]
   [:hired [:re #"\d{4}-\d{2}-\d{2}"]]
   [:dept [:string {:min 1}]]
   [:role [:string {:min 1}]]
   [:salary pos-int?]])

(defn- page [title & body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body
   (html
    [:html
     [:head
      [:title title]
      [:script {:src "https://unpkg.com/htmx.org@1.9.12"}]
      [:style "body{font-family:sans-serif;max-width:960px;margin:2em auto;padding:0 1em}
               table{border-collapse:collapse;width:100%}
               td,th{border:1px solid #ccc;padding:.35em .6em;text-align:left}
               header{border-bottom:3px double #888;margin-bottom:1em}
               .personality{color:#666;font-style:italic}
               .caps{background:#f6f6f6;padding:.6em;font-size:.85em}
               form.inline{display:inline}
               nav a{margin-right:1em}"]]
     [:body
      [:header
       [:h1 (:db db/info)]
       [:p.personality (:personality db/info)]
       [:nav
        [:a {:href "/"} "Employees"]
        [:a {:href "/asof"} "As-of payroll"]
        [:a {:href "/report"} "Report"]]]
      body]])})

(defn- caps-box [& ks]
  [:div.caps
   [:strong "How this database handles it: "]
   (str/join " · " (map #(str (name %) " — " (get-in db/info [:caps %])) ks))])

(defn- money [n] (when n (format "$%,d" n)))

(def ^:private per-page 25)

(defn index-page [dbh q page-n]
  ;; The roster is paginated: at 50k rows a full listing is neither
  ;; renderable nor cheap, so the db layer takes a LIMIT/OFFSET window and
  ;; the view offers prev/next. `page-n` is a 1-based page number from ?page.
  (let [pg   (max 1 (or (some-> page-n parse-long) 1))
        opts {:limit per-page :offset (* (dec pg) per-page)}
        rows (if (str/blank? q) (db/list-employees dbh opts) (db/search dbh q opts))
        href (fn [p] (str "/?"
                          (when-not (str/blank? q)
                            (str "q=" (java.net.URLEncoder/encode q "UTF-8") "&"))
                          "page=" p))]
    (page "Employees"
      [:h2 "Employees"]
      [:form {:method "get" :action "/"}
       [:input {:type "text" :name "q" :value (or q "")
                :placeholder "full-text search of review notes"}]
       [:button "Search"] " "
       (when-not (str/blank? q) [:a {:href "/"} "clear"])]
      (when-not (str/blank? q) (caps-box :search))
      (caps-box :migrations)
      [:table
       [:tr [:th "Name"] [:th "Dept"] [:th "Role"] [:th "Salary"] [:th "Last login"]
        (when-not (str/blank? q) [:th "Matched note"])]
       (for [e rows]
         [:tr
          [:td [:a {:href (str "/employee/" (:id e))} (:name e)]]
          [:td (:dept e)] [:td (:role e)] [:td (money (:salary e))]
          [:td (or (:last-login e) "—")]
          (when (:match e) [:td (:match e)])])]
      [:p (when (> pg 1) [:a {:href (href (dec pg))} "← prev"])
       " page " pg " "
       (when (= (count rows) per-page) [:a {:href (href (inc pg))} "next →"])]
      [:h3 "New employee"]
      [:form {:method "post" :action "/employee"}
       [:input {:name "given-name" :placeholder "given name"}]
       [:input {:name "family-name" :placeholder "family name"}]
       [:input {:name "email" :placeholder "email"}]
       [:input {:name "hired" :placeholder "hired yyyy-mm-dd"}]
       [:input {:name "dept" :placeholder "department"}]
       [:input {:name "role" :placeholder "role"}]
       [:input {:name "salary" :placeholder "salary"}]
       [:button "Create"]])))

(defn employee-page [dbh id]
  (if-let [e (db/get-employee dbh id)]
    (page (:name e)
      [:h2 (:name e) (when-not (:active e) " (offboarded)")]
      [:p (:email e) " · hired " (:hired e) " · " (:dept e) " / " (:role e)
       " · current salary " (money (:salary e))]
      [:form.inline {:method "post" :action (str "/employee/" id "/login")}
       [:button "Simulate login"]]
      " "
      [:form.inline {:method "post" :action (str "/employee/" id "/purge")
                     :onsubmit "return confirm('Purge all PII?')"}
       [:button "Offboard + purge PII"]]
      (caps-box :purge)
      [:h3 "Salary timeline"]
      [:table
       [:tr [:th "Amount"] [:th "Effective (valid time)"] [:th "Recorded (known since)"] [:th "Note"]]
       (for [s (:salary-timeline e)]
         [:tr [:td (money (:amount s))] [:td (:effective s)] [:td (:recorded s)] [:td (:note s)]])]
      [:h4 "Add salary change / backdated correction"]
      [:form {:method "post" :action (str "/employee/" id "/salary")}
       [:input {:name "amount" :placeholder "amount"}]
       [:input {:name "effective" :placeholder "effective yyyy-mm-dd"}]
       [:input {:name "note" :placeholder "note (e.g. 'backdated correction')"}]
       [:button "Record"]]
      [:h3 "Assignments"]
      [:table
       [:tr [:th "Dept"] [:th "Role"] [:th "Effective"]]
       (for [a (:assignments e)]
         [:tr [:td (:dept a)] [:td (:role a)] [:td (:effective a)]])]
      [:form {:method "post" :action (str "/employee/" id "/assign")}
       [:input {:name "dept" :placeholder "department"}]
       [:input {:name "role" :placeholder "role"}]
       [:input {:name "effective" :placeholder "effective yyyy-mm-dd"}]
       [:button "Assign"]]
      [:h3 "Review notes"]
      [:ul (for [r (:reviews e)] [:li [:strong (:date r)] " — " (:text r)])]
      [:form {:method "post" :action (str "/employee/" id "/review")}
       [:input {:name "text" :size 60 :placeholder "review note"}]
       [:button "Add note"]])
    (resp/not-found "no such employee")))

(defn asof-page [dbh valid known]
  (let [valid (or valid (str (java.time.LocalDate/now)))
        rows (db/asof-payroll dbh {:valid valid
                                   :known (when-not (str/blank? known) known)})]
    (page "As-of payroll"
      [:h2 "Payroll as of a date"]
      [:form {:method "get" :action "/asof"}
       "What was true on " [:input {:name "valid" :value valid}]
       " …as known by (optional) " [:input {:name "known" :value (or known "")}]
       [:button "Query"]]
      (caps-box :valid-axis :known-axis)
      [:table
       [:tr [:th "Name"] [:th "Dept"] [:th "Salary"]]
       (for [r rows]
         [:tr [:td (:name r)] [:td (:dept r)] [:td (money (:salary r))]])])))

(defn report-page [dbh]
  (page "Report"
    [:h2 "Average salary + headcount by department"]
    [:table
     [:tr [:th "Department"] [:th "Headcount"] [:th "Avg salary"]]
     (for [r (db/report dbh)]
       [:tr [:td (:dept r)] [:td (:headcount r)] [:td (money (:avg-salary r))]])]))

(defn- redirect [to] (resp/redirect to :see-other))

(defn- parse-long* [s] (try (Long/parseLong s) (catch Exception _ nil)))

(defn handler [dbh {:keys [request-method uri params] :as _req}]
  (let [p (vec (rest (str/split uri #"/")))]
    (case [request-method (mapv #(if (re-matches #"[0-9a-f-]{36}" %) :id %) p)]
      [:get []]                    (index-page dbh (get params "q") (get params "page"))
      [:get ["asof"]]              (asof-page dbh (get params "valid") (get params "known"))
      [:get ["report"]]            (report-page dbh)
      [:get ["employee" :id]]      (employee-page dbh (p 1))
      [:post ["employee"]]
      (let [m {:given-name (get params "given-name")
               :family-name (get params "family-name")
               :email (get params "email")
               :hired (get params "hired") :dept (get params "dept")
               :role (get params "role") :salary (parse-long* (get params "salary"))}]
        (if (m/validate employee-schema m)
          (do (db/create-employee! dbh m) (redirect "/"))
          {:status 400 :headers {"Content-Type" "text/plain"}
           :body (str "invalid: " (me/humanize (m/explain employee-schema m)))}))
      [:post ["employee" :id "salary"]]
      (do (db/add-salary-change! dbh (p 1) {:amount (parse-long* (get params "amount"))
                                            :effective (get params "effective")
                                            :note (get params "note")})
          (redirect (str "/employee/" (p 1))))
      [:post ["employee" :id "assign"]]
      (do (db/assign! dbh (p 1) {:dept (get params "dept") :role (get params "role")
                                 :effective (get params "effective")})
          (redirect (str "/employee/" (p 1))))
      [:post ["employee" :id "review"]]
      (do (db/add-review! dbh (p 1) (get params "text"))
          (redirect (str "/employee/" (p 1))))
      [:post ["employee" :id "login"]]
      (do (db/touch-login! dbh (p 1)) (redirect (str "/employee/" (p 1))))
      [:post ["employee" :id "purge"]]
      (do (db/purge! dbh (p 1)) (redirect "/"))
      (resp/not-found "not found"))))

(defn app
  "Build the ring handler over an (opaque) database handle."
  [dbh]
  (wrap-params (fn [req] (handler dbh req))))
