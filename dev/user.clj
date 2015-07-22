(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [clojure.repl :refer :all]
            [clojure.pprint :refer [pp pprint]]
            [cabin.handler :refer [app init destroy]]
            [aleph.http :as http]))

(def system nil)

(defn start [port]
  (let [port (if port (Long/parseLong port) 3000)]
    (init)
    (alter-var-root #'system
                    (constantly (http/start-server #'app {:port port})))))

(defn stop []
  (.close system)
  (destroy)
  (alter-var-root #'system (constantly nil)))

(defn go [& [port]]
  (start port))

(defn reset []
  (stop)
  (refresh :after 'user/go))
