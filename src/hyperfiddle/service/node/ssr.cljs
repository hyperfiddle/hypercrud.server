(ns hyperfiddle.service.node.ssr
  (:require [contrib.data :refer [unwrap]]
            [contrib.reactive :as r]
            [contrib.template :refer [load-resource]]
            [hypercrud.client.core :as hc]
            [hypercrud.client.peer :as peer]
            [hypercrud.transit :as transit]
            [hyperfiddle.appval.state.reducers :as reducers]
            [hyperfiddle.foundation :as foundation]
            [hyperfiddle.ide :as ide]
            [hyperfiddle.io.global-basis :refer [global-basis-rpc!]]
            [hyperfiddle.io.hydrate-requests :refer [hydrate-requests-rpc!]]
            [hyperfiddle.io.hydrate-route :refer [hydrate-route-rpc!]]
            [hyperfiddle.io.sync :refer [sync-rpc!]]
            [hyperfiddle.runtime :as runtime]
            [hyperfiddle.service.http :as http-service]
            [hyperfiddle.service.node.lib :as lib :refer [req->service-uri]]
            [hyperfiddle.state :as state]
            [promesa.core :as p]
            [reagent.dom.server :as reagent-server]
            [reagent.impl.template :as tmpl]
            [reagent.impl.util :as rutil]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]))


(defn render-to-node-stream
  [component]
  (ratom/flush!)
  (binding [rutil/*non-reactive* true]
    (.renderToNodeStream (reagent-server/module) (tmpl/as-element component))))

(def analytics (load-resource "analytics.html"))

(defn full-html [env state-val serve-js? params app-component]
  (let [resource-base (str (:STATIC_RESOURCES env) "/" (:BUILD env))]
    [:html {:lang "en"}
     [:head
      [:title "Hyperfiddle"]
      [:link {:rel "stylesheet" :href (str resource-base "/styles.css")}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:meta {:charset "UTF-8"}]
      [:script {:id "build" :type "text/plain" :dangerouslySetInnerHTML {:__html (:BUILD env)}}]]
     [:body
      [:div {:id "root"} app-component]
      (when (:ANALYTICS env)
        [:div {:dangerouslySetInnerHTML {:__html analytics}}])
      (when serve-js?
        ; env vars for client side rendering
        [:script {:id "params" :type "application/transit-json"
                  :dangerouslySetInnerHTML {:__html (transit/encode params)}}])
      (when serve-js?
        [:script {:id "state" :type "application/transit-json"
                  :dangerouslySetInnerHTML {:__html (transit/encode state-val)}}])
      (when serve-js?
        [:script {:id "preamble" :src (str resource-base "/preamble.js")}])
      (when serve-js?
        [:script {:id "main" :src (str resource-base "/main.js")}])]]))

(deftype IdeSsrRuntime [hyperfiddle-hostname hostname service-uri state-atom root-reducer]
  runtime/State
  (dispatch! [rt action-or-func] (state/dispatch! state-atom root-reducer action-or-func))
  (state [rt] state-atom)
  (state [rt path] (r/cursor state-atom path))

  runtime/AppFnGlobalBasis
  (global-basis [rt]
    (global-basis-rpc! service-uri))

  runtime/Route
  (decode-route [rt s]
    (ide/route-decode rt s))

  (encode-route [rt v]
    (ide/route-encode rt v))

  runtime/DomainRegistry
  (domain [rt]
    (ide/domain rt hyperfiddle-hostname hostname))

  runtime/AppValLocalBasis
  (local-basis [rt global-basis route branch branch-aux]
    (let [ctx {:hostname hostname
               :hyperfiddle-hostname hyperfiddle-hostname
               :branch branch
               :hyperfiddle.runtime/branch-aux branch-aux
               :peer rt}
          ; this is ide
          page-or-leaf (case (:hyperfiddle.ide/foo branch-aux)
                         "page" :page
                         "user" :leaf
                         "ide" :leaf)]
      (foundation/local-basis page-or-leaf global-basis route ctx ide/local-basis)))

  runtime/AppValHydrate
  (hydrate-route [rt local-basis route branch branch-aux stage]
    (hydrate-route-rpc! service-uri local-basis route branch branch-aux stage))

  runtime/AppFnHydrate
  (hydrate-requests [rt local-basis stage requests]
    (hydrate-requests-rpc! service-uri local-basis stage requests))

  runtime/AppFnSync
  (sync [rt dbs]
    (sync-rpc! service-uri dbs))

  runtime/AppFnRenderPageRoot
  (ssr [rt-page route]
    (let [ctx {:hostname hostname
               :hyperfiddle-hostname hyperfiddle-hostname
               :peer rt-page
               ::runtime/branch-aux {::ide/foo "page"}}
          alias (foundation/alias? (foundation/hostname->hf-domain-name hostname hyperfiddle-hostname))]
      [foundation/view :page route ctx (if alias (partial ide/view false) (constantly [:div "loading..."]))]))

  hc/Peer
  (hydrate [this branch request]
    (peer/hydrate state-atom branch request))

  (db [this uri branch]
    (peer/db-pointer uri branch))

  hc/HydrateApi
  (hydrate-api [this branch request]
    (unwrap @(hc/hydrate this branch request)))

  IHash
  (-hash [this] (goog/getUid this)))

(defn http-edge [env req res path-params query-params]
  (let [hostname (.-hostname req)
        hyperfiddle-hostname (http-service/hyperfiddle-hostname env hostname)
        user-profile (lib/req->user-profile env req)
        initial-state {:user-profile user-profile}
        rt (->IdeSsrRuntime hyperfiddle-hostname hostname (req->service-uri env req)
                            (r/atom (reducers/root-reducer initial-state nil))
                            reducers/root-reducer)
        load-level foundation/LEVEL-HYDRATE-PAGE
        browser-init-level (if (foundation/alias? (foundation/hostname->hf-domain-name hostname hyperfiddle-hostname))
                             load-level
                             ;force the browser to re-run the data bootstrapping when not aliased
                             foundation/LEVEL-NONE)
        alias? (foundation/alias? (foundation/hostname->hf-domain-name hostname hyperfiddle-hostname))]
    (-> (foundation/bootstrap-data rt foundation/LEVEL-NONE load-level (.-path req) (::runtime/global-basis initial-state))
        (p/then (fn []
                  (let [domain @(runtime/state rt [::runtime/domain])
                        owner (foundation/domain-owner? user-profile domain)
                        writable (and (not= "www" (:domain/ident domain))
                                      (or alias? owner))
                        action (if writable
                                 [:enable-auto-transact]
                                 [:disable-auto-transact])]
                    (runtime/dispatch! rt action))))
        (p/then (constantly 200))
        (p/catch #(or (:hyperfiddle.io/http-status-code (ex-data %)) 500))
        (p/then (fn [http-status-code]
                  (let [serve-js? (or (not alias?) (not @(runtime/state rt [::runtime/domain :domain/disable-javascript])))
                        params {:hyperfiddle-hostname hyperfiddle-hostname
                                :hyperfiddle.bootstrap/init-level browser-init-level}
                        html [full-html env @(runtime/state rt) serve-js? params
                              (runtime/ssr rt @(runtime/state rt [::runtime/partitions nil :route]))]]
                    (doto res
                      (.status http-status-code)
                      (.type "html")
                      (.write "<!DOCTYPE html>\n"))
                    (let [stream (render-to-node-stream html)]
                      (.on stream "error" (fn [e]
                                            (timbre/error e)
                                            (.end res (str "<h2>Fatal rendering error:</h2><h4>" (ex-message e) "</h4>"))))
                      (.pipe stream res)))))
        (p/catch (fn [e]
                   (timbre/error e)
                   (doto res
                     (.status (or (:hyperfiddle.io/http-status-code (ex-data e)) 500))
                     (.format #js {"text/html" #(.send res (str "<h2>Fatal error:</h2><h4>" (ex-message e) "</h4>"))})))))))
