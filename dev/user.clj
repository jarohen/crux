(ns user
  (:require [crux.api :as crux]
            [clojure.tools.namespace.repl :as ctn]
            [integrant.core :as i]
            [integrant.repl.state :refer [system]]
            [integrant.repl :as ir :refer [clear go suspend resume halt reset reset-all]]
            [crux.io :as cio]
            [crux.kafka.embedded :as ek]
            [clojure.java.io :as io]
            [clojure.test :as t])
  (:import (crux.api ICruxAPI)
           (java.io Closeable)))

(ctn/disable-reload!)

(def dev-node-dir
  (io/file "dev/dev-node"))

(defmethod i/init-key :node [_ node-opts]
  (crux/start-node node-opts))

(defmethod i/halt-key! :node [_ ^ICruxAPI node]
  (.close node))

(def standalone-config
  {:node {:crux.node/topology ['crux.standalone/topology
                               'crux.kv.rocksdb/kv-store
                               'crux.http-server/module]
          :crux.kv/db-dir (str (io/file dev-node-dir "db"))
          :crux.standalone/event-log-kv-store 'crux.kv.rocksdb/kv
          :crux.standalone/event-log-dir (str (io/file dev-node-dir "event-log"))
          :crux.kv/sync? true}})

(defmethod i/init-key :embedded-kafka [_ {:keys [kafka-port kafka-dir]}]
  (ek/start-embedded-kafka #::ek{:zookeeper-data-dir (str (io/file kafka-dir "zk-data"))
                                 :zookeeper-port (cio/free-port)
                                 :kafka-log-dir (str (io/file kafka-dir "kafka-log"))
                                 :kafka-port kafka-port}))

(defmethod i/halt-key! :embedded-kafka [_ ^Closeable embedded-kafka]
  (.close embedded-kafka))

(def embedded-kafka-config
  (let [kafka-port (cio/free-port)]
    {:embedded-kafka {:kafka-port kafka-port
                      :kafka-dir (io/file dev-node-dir "kafka")}
     :node {:crux.node/topology ['crux.kafka/topology
                                 'crux.kv.rocksdb/kv-store]
            :crux.kafka/bootstrap-servers (str "localhost:" kafka-port)
            :crux.kv/db-dir (str (io/file dev-node-dir "ek-db"))
            :crux.standalone/event-log-dir (str (io/file dev-node-dir "ek-event-log"))
            :crux.kv/sync? true}}))


;; swap for `embedded-kafka-config` to use embedded-kafka
(ir/set-prep! (fn [] standalone-config))

(defn crux-node []
  (:node system))

;;;; -----

(import 'java.time.Instant)

(defn update-idx [idx docs]
  (reduce (fn [idx [e doc]]
            (if (nil? doc)
              (dissoc idx e)
              (update idx e (fn [acc]
                              (reduce (fn [acc [a v]]
                                        (if (nil? v)
                                          (dissoc acc a)
                                          (assoc acc a v)))
                                      acc
                                      doc)))))
          idx
          docs))

(defn submit-ev! [!node ev]
  (-> (swap! !node
             (fn [node]
               (-> node
                   (assoc :tx-time (Instant/now))
                   (update :tx-id inc)
                   (update :evs conj ev)
                   (update :forms (fn [forms]
                                    (->> forms
                                         (into {} (map (juxt key
                                                             (comp (fn [{:keys [idx f] :as form}]
                                                                     (-> form
                                                                         (update :idx update-idx (f ev))))
                                                                   val))))))))))
      (select-keys [:tx-time :tx-id])))

(defn create-form! [!node k f]
  (-> (swap! !node (fn [{:keys [evs] :as node}]
                     (-> node
                         (assoc :form-tx-time (Instant/now))
                         (update :form-tx-id inc)
                         (assoc-in [:forms k]
                                   {:f f
                                    :idx (reduce (fn [idx ev]
                                                   (update-idx idx (f ev)))
                                                 {}
                                                 evs)}))))
      (select-keys [:form-tx-time :form-tx-id])))

(defn switch-forms! [!node statuses]
  (-> (swap! !node (fn [{:keys [form-tx-id] :as node}]
                     (let [form-tx-time (Instant/now)
                           form-tx-id (inc form-tx-id)]
                       (-> node
                           (assoc :form-tx-time form-tx-time)
                           (assoc :form-tx-id form-tx-id)
                           (update :forms (fn [forms]
                                            (reduce (fn [forms [k enabled?]]
                                                      (cond-> forms
                                                        (contains? forms k)
                                                        (update-in [k :history]
                                                                   (fnil conj '())
                                                                   [[form-tx-id form-tx-time] enabled?])))
                                                    forms
                                                    statuses)))))))
      (select-keys [:form-tx-time :form-tx-id])))

(defn evict-form! [!node k]
  (swap! !node
         (fn [node]
           (-> node
               (update :forms dissoc k)))))

(defn entity
  ([node eid] (entity node eid {}))

  ([{:keys [forms]} eid {:keys [form-tx-time form-tx-id]}]
   (some-> forms
           (->> (filter (let [p (comp (cond
                                        form-tx-id (comp #(> % form-tx-id) first)
                                        form-tx-time (comp #(.isAfter ^Instant % form-tx-time) second)
                                        :else (constantly false))
                                      first)]
                          (fn [[k {:keys [history]}]]
                            (->> history
                                 (drop-while p)
                                 first
                                 second))))
                vals
                (keep (comp #(get % eid) :idx))
                seq)
           (->> (into {} (mapcat seq))))))

(defn empty-node []
  (atom {:evs []
         :forms {}
         :form-tx-id 0
         :tx-id 0
         :current-forms #{}}))

(require '[clojure.test :as t])

(t/deftest e2e
  (let [!node (empty-node)]
    ;; submit an event. no forms yet, so we store the event and move on
    (def jms-ev
      (submit-ev! !node {::event-type :user-created,
                         :user-id :jms
                         :first-name "James"
                         :last-name "Henderson"
                         :email "jms@juxt.pro"}))

    ;; create the form, start indexing
    (create-form! !node :users
                  (fn [{::keys [event-type] :as ev}]
                    (case event-type
                      :user-created (let [{:keys [user-id]} ev]
                                      {user-id (select-keys ev [:user-id :first-name :last-name :email])})
                      nil)))

    ;; nothing as yet, need to enable the index
    (t/is (nil? (entity @!node :jms)))

    ;; enable the form
    (def checkpoint
      (switch-forms! !node {:users true}))

    (t/is (= {:user-id :jms, :first-name "James", :last-name "Henderson" :email "jms@juxt.pro"}
             (entity @!node :jms)))

    ;; we read an article on the internet regarding 'the myths developers believe about names', so we deprecate the old event

    (create-form! !node :users-v2
                  (fn [{::keys [event-type] :as ev}]
                    (case event-type
                      :user-created (let [{:keys [user-id first-name last-name]} ev]
                                      {user-id (-> (select-keys ev [:user-id :email])
                                                   (assoc :name (str first-name " " last-name)))})

                      :user-created-v2 (let [{:keys [user-id]} ev]
                                         {user-id (select-keys ev [:user-id :name :email])})

                      nil)))

    ;; ok, all good, let's switch the forms
    (switch-forms! !node {:users false, :users-v2 true})

    (t/is (= {:user-id :jms, :name "James Henderson" :email "jms@juxt.pro"}
             (entity @!node :jms)))

    ;; and add another user
    (def mal-ev
      (submit-ev! !node {::event-type :user-created-v2,
                         :user-id :mal
                         :name "Malcolm Sparks"
                         :email "mal@juxt.pro"}))

    ;; now they're both in the new structure
    (t/is (= {:user-id :jms, :name "James Henderson", :email "jms@juxt.pro"}
             (entity @!node :jms)))
    (t/is (= {:user-id :mal, :name "Malcolm Sparks", :email "mal@juxt.pro"}
             (entity @!node :mal)))

    ;; but we can still see the original
    (t/is (= {:user-id :jms, :first-name "James", :last-name "Henderson" :email "jms@juxt.pro"}
             (entity @!node :jms checkpoint)))

    ;; until we evict it, for disk space reasons
    (evict-form! !node :users)
    (t/is (nil? (entity @!node :jms checkpoint)))))
