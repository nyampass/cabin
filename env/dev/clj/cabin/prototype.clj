(ns cabin.prototype
  (:require
   [compojure.core :as compojure :refer [GET]]
   [ring.middleware.params :as params]
   [compojure.route :as route]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [manifold.bus :as bus]))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn echo-handler
    "The previous handler blocks until the websocket handshake completes, which unnecessarily
   takes up a thread.  This accomplishes the same as above, but asynchronously. "
    [req]
    (-> (http/websocket-connection req)
        (d/chain
         (fn [socket]
           (s/connect socket socket)))
        (d/catch
            (fn [_]
              non-websocket-request))))

;; to represent all the different chat rooms, we use an **event bus**, which is simple
;; implementation of the publish/subscribe model
(def chatrooms (bus/event-bus))

(defn chat-handler
  [req]
  (d/let-flow [conn (d/catch
                        (http/websocket-connection req)
                        (fn [_] nil))]

              (if-not conn

                ;; if it wasn't a valid websocket handshake, return an error
                non-websocket-request

                ;; otherwise, take the first two messages, which give us the chatroom and name
                (d/let-flow [room (s/take! conn)
                             name (s/take! conn)]

                            ;; take all messages from the chatroom, and feed them to the client
                            (s/connect
                             (bus/subscribe chatrooms room)
                             conn)

                            ;; take all messages from the client, prepend the name, and publish it to the room
                            (s/consume
                             #(bus/publish! chatrooms room %)
                             (->> conn
                                  (s/map #(str name ": " %))
                                  (s/buffer 100)))))))

(def handler
  (params/wrap-params
   (compojure/routes
    (GET "/echo" [] echo-handler)
    (GET "/chat" [] chat-handler)
    (route/not-found "No such page."))))

(defn start-server []
  (http/start-server handler {:port 10000}))
