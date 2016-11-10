(ns riemann.plugin.kafka
  "A riemann plugin to consume and produce from and to a kafka queue"
  (:import com.aphyr.riemann.Proto$Msg
           kafka.consumer.KafkaStream
           kafka.producer.KeyedMessage)
  (:require [riemann.core          :refer [stream!]]
            [riemann.common        :refer [decode-msg encode]]
            [clj-kafka.core        :refer [to-clojure]]
            [clj-kafka.consumer.zk :refer [consumer]]
            [clj-kafka.producer    :refer [send-message producer]]
            [riemann.service       :refer [Service ServiceEquiv]]
            [riemann.config        :refer [service!]]
            [clojure.tools.logging :refer [debug info error]]))

(defn protobuf-decoder
  "Decode protobuf object to riemann events"
  [value]
  (:events (decode-msg (Proto$Msg/parseFrom value))))

(defn safe-decode
  "Do not let a bad payload break our consumption"
  [decoder input]
  (try
    (let [{:keys [value]} (to-clojure input)]
      (decoder value))
    (catch Exception e
      (error e "could not decode msg"))))

(defn stringify
  "Prepare a map to be converted to properties"
  [props]
  (let [input (dissoc props :topic :decoder :encoder :commit.per.msg)
        skeys (map (juxt (comp name key) val) input)]
    (reduce merge {} skeys)))

(defn start-kafka-thread
  "Start a kafka thread which will pop messages off of the queue as long
   as running? is true"
  [running? core {:keys [topic] :as config}]
  (let [inq (consumer (stringify config))]
    (future
      (info "in consumption thread with consumer: " inq)
      (try
        (let [stream-map     (.createMessageStreams inq {topic (int 1)})
              [stream & _]   (get stream-map topic)
              msg-iterator   (.iterator ^KafkaStream stream)
              decoder        (:decoder config protobuf-decoder)
              commit-per-msg (:commit.per.msg config true)]
          (when (not commit-per-msg)
            (info "Commission from riemann-kafka consumer is disabled."
                  "DO NOT disable :auto.commit.enable in your consumer config."))
          (while (.hasNext msg-iterator)
            (doseq [event (safe-decode decoder (.next msg-iterator))]
              (debug "got input event: " event)
              (stream! @core event))
            (when commit-per-msg (.commitOffsets inq)))
          (info "was instructed to stop, BYE!"))
        (catch Exception e
          (error e "interrupted consumption"))
        (finally
          (.shutdown inq))))))

(defn kafka-consumer
  "Yield a kafka consumption service"
  [config]
  (service!
   (let [running? (atom true)
         core     (atom nil)]
     (reify
       clojure.lang.ILookup
       (valAt [this k not-found]
         (or (.valAt this k) not-found))
       (valAt [this k]
         (info "looking up: " k)
         (if (= (name k) "config") config))
       ServiceEquiv
       (equiv? [this other]
         (= config (:config other)))
       Service
       (conflict? [this other]
         (= config (:config other)))
       (start! [this]
         (info "starting kafka consumer running for topics: "
               (:topic config))
         (start-kafka-thread running? core
                             (merge {:topic "riemann"} config)))
       (reload! [this new-core]
         (info "reload called, setting new core value")
         (reset! core new-core))
       (stop! [this]
         (reset! running? false)
         (info "kafka consumer stopping"))))))

(defn kafka-producer
  "Yield a kafka producer"
  [{:keys [topic] :as config}]
  (let [p (producer (stringify config))]
    (fn [event]
      (let [events (if (sequential? event) event [event])
            encoder (or (:encoder config) encode)]
        (send-message p (KeyedMessage. topic (encoder events)))))))
