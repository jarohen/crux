(ns crux.ingest-microbench
  (:require [crux.api :as crux]
            [clojure.test :as t]
            [crux.fixtures :as fix :refer [*api*]]
            [crux.rocksdb :as rocks]
            [clojure.java.io :as io]))

(t/use-fixtures :each
  (fn [f]
    (let [data-dir (io/file "/tmp/rocks-microbench")]
      (fix/with-tmp-dir "rocks" [tmp-data-dir]
        (fix/with-opts {:crux/tx-log {:kv-store {:crux/module `rocks/->kv-store, :db-dir (io/file data-dir "tx-log")}}
                        :crux/document-store {:kv-store {:crux/module `rocks/->kv-store, :db-dir (io/file data-dir "doc-store")}}
                        :crux/index-store {:kv-store {:crux/module `rocks/->kv-store, :db-dir (io/file tmp-data-dir "indexes")}}}
          f))))
  fix/with-node)

(comment
  (t/deftest test-microbench
    (time
     (do
       (when-not (crux/latest-submitted-tx *api*)
         (dotimes [n 25]
           (fix/submit+await-tx (for [doc-id (range 1000)]
                                  [:crux.tx/put {:crux.db/id (+ (* n 1000) doc-id)}]))))
       (crux/sync *api*)))

    (t/is (= 25000
             (count (crux/q (crux/db *api*)
                            '{:find [?e]
                              :where [[?e :crux.db/id]]}))))))
