(ns cabin.websocket
  (:refer-clojure :exclude [send])
  (:require
   [compojure.core :refer :all]
   [ring.middleware
    [format :refer [wrap-restful-format]]]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold
    [stream :as s]
    [deferred :as d]]
   [digest :as digest]
   [clojure.data.json :as json]))

(defonce peers (atom {}))

(defn prefix-of [peer-id]
  (subs peer-id 0 4))

(defn matching-peers [peer-id]
  (assert (string? peer-id))
  (assert (>= (count peer-id) 4))
  (let [prefix (prefix-of peer-id)]
    (when-let [prefix-mathing-peers (get @peers prefix)]
      (or (some-> (find prefix-mathing-peers peer-id) list)
          (filter #(.startsWith (key %) peer-id) prefix-mathing-peers)))))

(defn find-matching-peer [peer-id]
  (when (and (string? peer-id) (>= (count peer-id) 4))
    (when-let [peers (matching-peers peer-id)]
      (when (= (count peers) 1)
        (let [[peer-id props] (first peers)]
          {:peer-id peer-id :props props})))))

(defn coerce-to-peer [id-or-peer]
  (if (map? id-or-peer)
    id-or-peer
    (find-matching-peer id-or-peer)))

(defn with-peer-props [id-or-peer f]
  (when-let [peer (coerce-to-peer id-or-peer)]
    (f (:props peer))))

(defn update-peer [peers id-or-peer f & args]
  (let [{:keys [peer-id]} (coerce-to-peer id-or-peer)
        prefix (prefix-of peer-id)]
    (apply update-in peers [prefix peer-id] f args)))

(defn connection-for [id-or-peer]
  (with-peer-props id-or-peer :conn))

(defn register-peer! [{ip-address :remote-addr} conn]
  (let [now (with-out-str
              (#'clojure.instant/print-date (java.util.Date.) *out*))
        new-id (digest/sha1 (str ip-address now))
        prefix (prefix-of new-id)]
    (swap! peers assoc-in [prefix new-id :conn] conn)
    new-id))

(defn unregister-peer! [exact-peer-id]
  (let [prefix (prefix-of exact-peer-id)]
    (swap! peers update-in [prefix] dissoc exact-peer-id)))

(defn send-message [conn message]
  (s/put! conn (json/write-str message)))

(defn send [id-or-peer message]
  (send-message (connection-for id-or-peer) message))

(defn connect [req]
  (d/let-flow [conn (http/websocket-connection req)]
    (let [new-id (register-peer! req conn)]
      (s/on-closed conn #(unregister-peer! new-id))
      (send-message conn {:type :connected :peer-id new-id})
      conn)))

(defn receiver? [id-or-peer]
  (with-peer-props id-or-peer :receiver?))

(defn password-for [id-or-peer]
  (with-peer-props id-or-peer :password))

(defn promote-to-receiver! [id-or-peer pass]
  (swap! peers update-peer id-or-peer merge {:receiver? true :password pass}))

(defn demote-to-client! [id-or-peer]
  (swap! peers update-peer id-or-peer dissoc :receiver? :password))

(defmulti handle-message (fn [from message] (keyword (:type message))))

(defmethod handle-message :default [from {:keys [to password] :as message}]
  (let [dest (find-matching-peer to)]
    (cond (nil? dest)
          #_=> (send from {:type :error :cause :invalid-receiver})
          (not (receiver? dest))
          #_=> (send from {:type :error :cause :not-receiver})
          (or (nil? password) (not= password (password-for dest)))
          #_=> (send from {:type :error :cause :authorization-failed})
          :else (let [message (-> message
                                  (assoc :to (:peer-id dest))
                                  (dissoc :password))]
                  (send dest message)))))

(defmethod handle-message :promote [from message]
  (if-let [password (:password message)]
    (do (promote-to-receiver! from password)
        (send from {:type :promote :status :ok}))
    (send from {:type :promote :status :error :cause :password-required})))

(defmethod handle-message :demote [from message]
  (if (receiver? from)
    (do (demote-to-client! from)
        (send from {:type :demote :status :ok}))
    (send from {:type :demote :status :error :cause :not-receiver})))

(defn on-message-handler [conn]
  (fn [raw-message]
    (if-let [message (try (json/read-str raw-message :key-fn keyword)
                          (catch Exception _))]
      (if-let [from (find-matching-peer (:from message))]
        (if (= (connection-for from) conn)
          (let [message (assoc message :from (:peer-id from))]
            (handle-message from message))
          (send-message conn {:type :error :cause :invalid-sender}))
        (send-message conn {:type :error :cause :invalid-sender}))
      (send-message conn {:type :error :cause :invalid-json}))))

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

(defroutes websocket-handler*
  (GET "/ws" req
       (start-connection req)))

(def websocket-handler
  (wrap-restful-format websocket-handler* {:formats [:json-kw]}))
