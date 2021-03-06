(ns cabin.websocket
  (:refer-clojure :exclude [send])
  (:require
   [compojure.core :refer :all]
   [taoensso.timbre :as timbre]
   [ring.middleware
    [format :refer [wrap-restful-format]]]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold
    [stream :as s]
    [deferred :as d]]
   [digest :as digest]
   [clojure.data.json :as json]
   [clj-time.core :as t]
   [cabin.db.custom-name :as cn]))

(defonce peers (atom {}))

(defprotocol PeerLike
  (coerce-to-peer [this]))

(defrecord Peer [peer-id conn receiver? password])

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
        (val (first peers))))))

(extend-protocol PeerLike
  Peer
  (coerce-to-peer [this] this)
  String
  (coerce-to-peer [this]
    (if-let [peer-id (cn/peer-id-for this)]
      (find-matching-peer peer-id)
      (find-matching-peer this))))

(defn with-peer [peer-like f]
  (when-let [peer (coerce-to-peer peer-like)]
    (f peer)))

(defn update-peer [peers peer-like f & args]
  (let [{:keys [peer-id]} (coerce-to-peer peer-like)
        prefix (prefix-of peer-id)]
    (apply update-in peers [prefix peer-id] f args)))

(defn connection-for [peer-like]
  (with-peer peer-like :conn))

(defn register-peer! [{ip-address :remote-addr} conn]
  (let [now (t/now)
        new-id (digest/sha1 (str ip-address now))
        prefix (prefix-of new-id)]
    (swap! peers assoc-in [prefix new-id]
           (map->Peer {:peer-id new-id :conn conn}))
    new-id))

(defn unregister-peer! [peer-id]
  (let [prefix (prefix-of peer-id)]
    (swap! peers update-in [prefix] dissoc)))

(defn send-message [conn message]
  (s/put! conn (json/write-str message)))

(defn send [peer-like message]
  (let [peer (coerce-to-peer peer-like)]
    (timbre/debug "message sent:" message "to" (:peer-id peer)))
  (send-message (connection-for peer-like) message))

(defn connect [req]
  (d/let-flow [conn (http/websocket-connection req)]
    (let [new-id (register-peer! req conn)]
      (s/on-closed conn #(unregister-peer! new-id))
      (send-message conn {:type :connected :peer-id new-id})
      conn)))

(defn receiver? [peer-like]
  (with-peer peer-like :receiver?))

(defn password-for [peer-like]
  (with-peer peer-like :password))

(defn promote-to-receiver! [peer-like pass]
  (let [pass (digest/sha1 pass)]
    (swap! peers update-peer peer-like merge {:receiver? true :password pass})))

(defn demote-to-client! [peer-like]
  (swap! peers update-peer peer-like merge {:receiver? false :password nil}))

(defmulti handle-message (fn [from message] (keyword (:type message))))

(defmethod handle-message :promote [from message]
  (letfn [(respond [status & {:as opts}]
            (send from (merge {:type :promote :status status} opts)))
          (promote! [from password]
            (promote-to-receiver! from password)
            (respond :ok))]
    (if-let [password (:password message)]
      (let [{name :custom-name name-pass :custom-name-password} message]
        (cond (and (not name) (not name-pass))
              #_=> (promote! from password)
              (and name name-pass)
              #_=> (if (cn/register-custom-name! (:peer-id from) name name-pass)
                     (promote! from password)
                     (respond :error :cause :custom-name-already-in-use))
              :else (respond :error :cause :custom-name-not-enabled)))
      (respond :error :cause :password-required))))

(defmethod handle-message :demote [from message]
  (if (receiver? from)
    (do (demote-to-client! from)
        (send from {:type :demote :status :ok}))
    (send from {:type :demote :status :error :cause :not-receiver})))

(defn with-valid-destination [to from f]
  (if-let [dest (coerce-to-peer to)]
    (f dest)
    (send from {:type :error :cause :invalid-receiver})))

(defmethod handle-message :result [from {:keys [to] :as message}]
  (with-valid-destination to from
    (fn [dest]
      (send dest (assoc message :to (:peer-id dest))))))

(defmethod handle-message :event [from {:keys [to] :as message}]
  (with-valid-destination to from
    (fn [dest]
      (send dest (assoc message :to (:peer-id dest))))))

(defmethod handle-message :default [from {:keys [to password] :as message}]
  (with-valid-destination to from
    (fn [dest]
      (cond (not (receiver? dest))
            #_=> (send from {:type :error :cause :not-receiver})
            (and (not= (:type message) :result)
                 (or (nil? password)
                     (not= (digest/sha1 password) (password-for dest))))
            #_=> (send from {:type :error :cause :authorization-failed})
            :else (let [message (-> message
                                    (assoc :to (:peer-id dest))
                                    (dissoc :password))]
                    (send dest message))))))

(defn on-message-handler [conn]
  (fn [raw-message]
    (if-let [message (try (json/read-str raw-message :key-fn keyword)
                          (catch Exception _))]
      (do (timbre/debug "message received:" message)
          (if-let [from (find-matching-peer (:from message))]
            (if (= (connection-for from) conn)
              (let [message (assoc message :from (:peer-id from))]
                (handle-message from message))
              (send-message conn {:type :error :cause :invalid-sender}))
            (send-message conn {:type :error :cause :invalid-sender})))
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
