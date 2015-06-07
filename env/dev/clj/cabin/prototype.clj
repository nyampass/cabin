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
   [digest :as digest]))

(defonce ids (atom {}))
(defonce connections (atom {}))
(defonce receiver (atom nil))

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

(defn issue-id [{:keys [remote-addr]}]
  (let [time (with-out-str
               (#'clojure.instant/print-date (java.util.Date.) *out*))
        new-id (digest/sha1 (str remote-addr time))]
    (swap! ids assoc new-id remote-addr)
    (res/response {:status :ok :id new-id})))

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

(defroutes handler
  (POST "/id" req
        (issue-id req))
  (POST "/receiver" req
        (register-receiver req))
  (POST "/sender" req
        (register-sender req))
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
