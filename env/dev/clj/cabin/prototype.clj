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

(defn ws-handler [req]
  (-> (http/websocket-connection req)
      (d/chain
       (fn [socket]
         (s/connect socket socket)))
      (d/catch
          (fn [_]
            non-websocket-request))))

(defn register-receiver [{{:keys [id password]} :params :as req}]
  (if-not (contains? @ids id)
    (res/response {:status :error :cause :invalid-id})
    (do (when @receiver
          ;; FIXME: previous receiver should be reported revocation
          nil)
        (reset! receiver {:id id :password password})
        (res/response {:status :ok :id id}))))

(defn register-sender [{{:keys [id session password]} :params}]
  (if-not (contains? @ids id)
    (res/response {:status :error :cause :invalid-id})
    (let [receiver @receiver]
      (if (and (= session (:id receiver))
               (= password (:password receiver)))
        (res/response {:status :ok :id id})
        (res/response {:status :error :cause :authentication-failure})))))

(defn register-connection! [{ip-address :remote-addr} conn]
  (let [now (with-out-str
              (#'clojure.instant/print-date (java.util.Date.) *out*))
        new-id (digest/sha1 (str ip-address now))]
    (swap! connections assoc-in [new-id :conn] conn)
    new-id))

(defn unregister-id! [id]
  (swap! connections dissoc id))

(defn connect [req]
  (d/let-flow [conn (http/websocket-connection req)]
    (let [new-id (register-connection! req conn)]
      (s/on-closed conn #(unregister-id! new-id))
      (s/put! conn (json/write-str {:type :connected :id new-id}))
      conn)))

(defn start-connection [req]
  (d/let-flow [conn (connect req)]
    (-> (s/connect conn conn)
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
