(ns cabin.db.core
  (:require [environ.core :refer [env]]
            [monger.core :as mg]))

(defonce db (-> (env :mongo-uri) mg/connect-via-uri :db))
