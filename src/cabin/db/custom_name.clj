(ns cabin.db.custom-name
  (:require [cabin.db.core :refer [db]]
            [monger.collection :as mc]
            [digest :as digest])
  (:import java.util.Date
           com.mongodb.DuplicateKeyException))

(def COLL_NAME "custom-names")

(defn fix-custom-name [custom-name]
  (-> custom-name
      (assoc :name (:_id custom-name))
      (dissoc :_id)))

(defn register-custom-name! [peer-id name password]
  (let [password (digest/sha1 password)
        custom-name {:_id name
                     :peer-id peer-id
                     :password password
                     :last-accessed-at (Date.)}]
    (try
      (mc/update db COLL_NAME
                 {:_id name :password password}
                 custom-name
                 {:upsert true})
      (catch DuplicateKeyException e
        nil))))

(defn unregister-custom-name! [name password]
  (let [password (digest/sha1 password)]
    (mc/remove db COLL_NAME {:_id name :password password})))

(defn peer-id-for [name]
  (some-> (mc/find-one-as-map db COLL_NAME {:_id name})
          :peer-id))
