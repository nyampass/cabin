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

(defonce peers (atom {}))

(defonce debug (atom nil))

(defn connection-for [peer-id]
  (get-in @peers [peer-id :conn]))

(defn register-peer! [{ip-address :remote-addr} conn]
  (let [now (with-out-str
              (#'clojure.instant/print-date (java.util.Date.) *out*))
        new-id (digest/sha1 (str ip-address now))]
    (swap! peers assoc-in [new-id :conn] conn)
    new-id))

(defn valid-peer? [peer-id]
  (contains? @peers peer-id))

(defn unregister-peer! [peer-id]
  (swap! peers dissoc peer-id))

(defn send-message [conn message]
  (s/put! conn (json/write-str message)))

(defn connect [req]
  (d/let-flow [conn (http/websocket-connection req)]
    (let [new-id (register-peer! req conn)]
      (s/on-closed conn #(unregister-peer! new-id))
      (send-message conn {:type :connected :peer-id new-id})
      conn)))

(defn server? [peer-id]
  (get-in @peers [peer-id :server?]))

(defn password-for [peer-id]
  (get-in @peers [peer-id :password]))

(defn promote-to-server! [peer-id pass]
  (swap! peers update-in [peer-id] merge {:server? true :password pass}))

(defn demote-to-client! [peer-id]
  (swap! peers update-in [peer-id] dissoc :server? :password))

(defmulti handle-message (comp keyword :type))

(defmethod handle-message :default [{:keys [from to password] :as message}]
  (let [conn (connection-for from)]
    (cond (or (nil? to) (not (valid-peer? to)))
          #_=> (send-message conn {:type :error :cause :invalid-destination})
          (not (server? to))
          #_=> (send-message conn {:type :error :cause :nonserver-destination})
          (or (nil? password) (not= password (password-for to)))
          #_=> (send-message conn {:type :error :cause :authorization-failed})
          :else (send-message (connection-for to) (dissoc message :password)))))

(defmethod handle-message :promote [{:keys [from] :as message}]
  (let [conn (connection-for from)]
    (if-let [password (:password message)]
      (do (promote-to-server! from password)
          (send-message conn {:type :promote :status :ok}))
      (send-message conn {:type :promote
                          :status :error
                          :cause :password-missing}))))

(defmethod handle-message :demote [{:keys [from] :as message}]
  (let [conn (connection-for from)]
    (if (server? from)
      (do (demote-to-client! from)
          (send-message conn {:type :demote :status :ok}))
      (send-message conn {:type :demote
                          :status :error
                          :cause :not-server}))))

(defn on-message-handler [conn]
  (fn [raw-message]
    (let [message (json/read-str raw-message :key-fn keyword)
          {:keys [type from]} message]
      (if (valid-peer? from)
        (handle-message message)
        (send-message conn {:type :error :cause :invalid-from})))))

(defn start-connection [req]
  (d/let-flow [conn (connect req)]
    (-> (s/consume (on-message-handler conn) conn)
        (d/catch
          (fn [_]
            (when-not (s/closed? conn)
              (send-message conn {:type :error
                                  :cause :unexpected-error}))
            ;; TODO: postprocess should be performed such as unregistration
            )))))

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
