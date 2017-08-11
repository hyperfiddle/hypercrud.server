(ns hypercrud.server.datomic.core
  (:require [datomic.api :as d]
            [hypercrud.server.database :as database]
            [hypercrud.server.db-root :as db]))


(defn init-datomic [transactor-uri]
  (let [root-uri (str transactor-uri "root")
        db-created? (d/create-database root-uri)]           ;idempotent
    (when db-created?
      (d/delete-database root-uri)                          ; clean up
      (throw (Error. "Must seed with a real root-db, can't bootstrap from nothing anymore.")))

    (alter-var-root #'db/transactor-uri (constantly transactor-uri))
    (if-let [root-id (d/q '[:find ?db . :where
                            [?a :db/ident :database/ident]
                            [?db ?a "root"]]
                          (d/db (database/get-root-conn)))]
      (alter-var-root #'db/root-id (constantly root-id))
      (throw (Error. "Database registry not configured")))))