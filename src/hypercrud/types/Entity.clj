(ns hypercrud.types.Entity
  (:import [com.cognitect.transit WriteHandler ReadHandler]
           [clojure.lang ILookup IHashEq]))


(deftype Entity [dbval coll]
  IHashEq (hasheq [this] (hash [dbval (:db/id coll)]))
  Object (equals [this other]
           (and (instance? Entity other)
                (= (.dbval this) (.dbval other))
                (= (:db/id (.coll this)) (:db/id (.coll other)))))
  ILookup
  (valAt [o k] (get coll k))
  (valAt [o k not-found] (get coll k not-found)))

(defmethod print-method Entity [o ^java.io.Writer w]
  (.write w (str "#Entity" (pr-str [(.dbval o) (.coll o)]))))

(defmethod print-dup Entity [o w]
  (print-method o w))

(def read-Entity #(apply ->Entity %))

(deftype EntityTransitHandler []
  WriteHandler
  (tag [_ v] "Entity")
  (rep [_ v] [(.dbval v) (.coll v)])
  (stringRep [_ v] nil)
  (getVerboseHandler [_] nil))


(deftype EntityTransitReader []
  ReadHandler
  (fromRep [_ v] (apply ->Entity v)))
