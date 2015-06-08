(ns cabin.prototype
  (:require
   [compojure.core :refer :all]
   [ring.util.response :as res]
   [ring.middleware
    [params :refer [wrap-params]]
    [format :refer [wrap-restful-format]]]
   [compojure.route :as route]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [manifold.bus :as bus]
   [taoensso.timbre :as timbre]
   [digest :as digest]
   [clojure.data.json :as json]))

(defonce connections (atom {}))

(defonce debug (atom nil))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn connection-for [id]
  (get-in @connections [id :conn]))

(defn register-connection! [{ip-address :remote-addr} conn]
  (let [now (with-out-str
              (#'clojure.instant/print-date (java.util.Date.) *out*))
        new-id (digest/sha1 (str ip-address now))]
    (swap! connections assoc-in [new-id :conn] conn)
    new-id))

(defn valid-id? [id]
  (contains? @connections id))

(defn unregister-id! [id]
  (swap! connections dissoc id))

(defn send-message [conn message]
  (s/put! conn (json/write-str message)))

(defn connect [req]
  (d/let-flow [conn (http/websocket-connection req)]
    (let [new-id (register-connection! req conn)]
      (s/on-closed conn #(unregister-id! new-id))
      (send-message conn {:type :connected :id new-id})
      conn)))

(defn server? [id]
  (get-in @connections [id :server?]))

(defn promote-to-server! [id pass]
  (swap! connections update-in [id] merge {:server? true :password pass}))

(defn demote-to-client! [id]
  (swap! connections update-in [id] dissoc :server? :password))

(defn on-message [raw-message]
  (let [message (json/read-str raw-message :key-fn keyword)
        {:keys [type from]} message]
    (if (valid-id? from)
      (case type
        "promote"
        #_=> (let [conn (connection-for from)]
               (if-let [password (:password message)]
                 (do (promote-to-server! from password)
                     (send-message conn {:type :promote :status :ok}))
                 (send-message conn {:type :promote
                                     :status :error
                                     :cause :password-missing})))
        "demote"
        #_=> (let [conn (connection-for from)]
               (if (server? from)
                 (do (demote-to-client! from)
                     (send-message conn {:type :demote :status :ok}))
                 (send-message conn {:type :demote
                                     :status :error
                                     :cause :not-server})))))))

(defn start-connection [req]
  (d/let-flow [conn (connect req)]
    (-> (s/consume #(on-message %) conn)
        (d/catch (constantly non-websocket-request)))))

(defroutes handler
  (GET "/ws" req
       (start-connection req))
  (route/not-found "No such page."))

(def app
  (-> handler
      (wrap-restful-format {:formats [:json-kw]})))

(defonce server (atom nil))

(defn start-server []
  (let [s (http/start-server #'app {:port 10000})]
    (reset! server s)
    s))

(defn stop-server []
  (let [s @server]
    (reset! server nil)
    (.close s)))
