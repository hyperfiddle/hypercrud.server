(ns hypercrud.server.api
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [datomic.api :as d]
            [hypercrud.types.DbVal]
            [hypercrud.types.DbError :refer [->DbError]]
            [hypercrud.types.Entity :refer [->Entity]]
            [hypercrud.types.EntityRequest]
            [hypercrud.types.QueryRequest]
            [hypercrud.util.core :as util]
            [hypercrud.util.identity :as identity])
  (:import (hypercrud.types.DbVal DbVal)
           (hypercrud.types.EntityRequest EntityRequest)
           (hypercrud.types.QueryRequest QueryRequest)))


(defmulti parameter (fn [this & args] (class this)))

(defmethod parameter :default [this & args] this)

(defmethod parameter DbVal [dbval get-secure-db-with]
  (-> (get-secure-db-with (:uri dbval) (:branch dbval)) :db))

(defn recursively-add-entity-types [pulled-tree dbval]
  (walk/postwalk (fn [o]
                   (if (:db/id o)
                     (->Entity dbval o)
                     o))
                 pulled-tree))

(defmulti hydrate* (fn [this & args] (class this)))

(defmethod hydrate* EntityRequest [{:keys [e a dbval pull-exp]} get-secure-db-with]
  (try
    (let [{pull-db :db} (get-secure-db-with (:uri dbval) (:branch dbval))
          pull-exp (if a [{a pull-exp}] pull-exp)
          pulled-tree (if (identity/tempid? e)
                        (if a
                          []                                ; hack, return something that is empty for base.cljs
                          ; todo return a positive id here
                          {:db/id e})
                        (d/pull pull-db pull-exp e))
          pulled-tree (recursively-add-entity-types pulled-tree dbval)
          pulled-tree (if a (get pulled-tree a []) pulled-tree)]
      pulled-tree)
    (catch Throwable e
      (.println *err* (pr-str e))
      (->DbError (str e)))))

(defmethod hydrate* QueryRequest [{:keys [query params pull-exps]} get-secure-db-with]
  (try
    (assert query "hydrate: missing query")
    (let [ordered-params (->> (util/parse-query-element query :in)
                              (mapv #(get params (str %)))
                              (mapv #(parameter % get-secure-db-with)))
          ordered-find-element-symbols (util/parse-query-element query :find)
          ordered-fe-names (mapv str ordered-find-element-symbols)
          pull-exps (->> ordered-fe-names
                         (map (juxt identity (fn [fe-name]
                                               (let [pull-exp (get pull-exps fe-name)]
                                                 (assert (not= nil pull-exp) (str "hydrate: missing pull expression for " fe-name))
                                                 pull-exp))))
                         (into {}))]
      (->> (apply d/q query ordered-params)                 ;todo gaping security hole
           (mapv (fn [relation]
                   (->> (map (fn [fe-name eid]
                               (let [[dbval pull-exp] (get pull-exps fe-name)
                                     {pull-db :db} (get-secure-db-with (:uri dbval) (:branch dbval))
                                     pulled-tree (-> (d/pull pull-db pull-exp eid)
                                                     (recursively-add-entity-types dbval))]
                                 [fe-name pulled-tree]))
                             ordered-fe-names relation)
                        (into {}))))))

    (catch Throwable e
      (.println *err* (pr-str e))
      (->DbError (str e)))))

(defn build-get-secure-db-with [hctx-groups db-with-lookup]
  (fn get-secure-db-with [uri branch]
    (or (get-in @db-with-lookup [uri branch])
        (let [dtx (get hctx-groups [uri branch])
              db (d/db (d/connect (str uri)))               ; todo fix inconsistent t across branches
              ; is it a history query? (let [db (if (:history? dbval) (d/history db) db)])
              _ (let [validate-tx (constantly true)]
                  ; todo look up tx validator
                  (assert (validate-tx db dtx) (str "staged tx for " uri " failed validation")))
              project-db-with (let [read-sec-predicate (constantly true) ;todo lookup sec pred
                                    ; todo d/with an unfiltered db
                                    {:keys [db-after tempids]} (d/with db dtx)]
                                {:db (d/filter db-after read-sec-predicate)
                                 :id->tempid (set/map-invert tempids)})]
          (swap! db-with-lookup assoc-in [uri branch] project-db-with)
          project-db-with))))

(defn hydrate [dtx-groups request root-t]
  (let [db-with-lookup (atom {})
        get-secure-db-with (build-get-secure-db-with dtx-groups db-with-lookup)
        pulled-trees-map (->> request
                              (mapv (juxt identity #(hydrate* % get-secure-db-with)))
                              (into {}))]
    {:t nil
     :pulled-trees-map pulled-trees-map
     :id->tempid (->> @db-with-lookup                       ; todo this is broken (cannot be lazy)
                      (util/map-values #(util/map-values :id->tempid %)))}))

(defn transact! [dtx-groups]
  (let [valid? (every? (fn [[uri tx]]
                         (let [db (d/db (d/connect (str uri)))
                               ; todo look up tx validator
                               validate-tx (constantly true)]
                           (validate-tx db tx)))
                       dtx-groups)]
    (if-not valid?
      (throw (RuntimeException. "user tx failed validation"))
      (let [tempid-lookups (->> dtx-groups
                                (mapv (fn [[uri dtx]]
                                        (let [{:keys [tempids]} @(d/transact (d/connect (str uri)) dtx)]
                                          [uri tempids])))
                                (into {}))]
        {:tempid->id tempid-lookups}))))

(defn latest [conn]
  (str (-> (d/db conn) d/basis-t)))
