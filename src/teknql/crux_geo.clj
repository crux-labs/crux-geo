(ns teknql.crux-geo
  (:require [crux.bus :as bus]
            [crux.codec :as cc]
            [crux.system :as sys]
            [crux.db :as db]
            [crux.query :as q]
            [clojure.spec.alpha :as s])
  (:import [crux.query VarBinding]
           [org.locationtech.jts.index.strtree STRtree GeometryItemDistance]
           [org.locationtech.jts.geom Geometry Point GeometryFactory PrecisionModel
            Coordinate LinearRing Polygon MultiPoint MultiPolygon LineString]))

(defrecord DocID [a v])

(def ^:private geo-factory
  (GeometryFactory. (PrecisionModel.) 4326))

(defn- ->coordinate
  "Return a coordinate from the provided `x y` vector."
  ^Coordinate
  [[x y]]
  (Coordinate. x y))

(defn- ->coordinates
  "Return a java array of coordinates from the provided seqable of `[x y]` vectors"
  ^"[Lorg.locationtech.jts.geom.Coordinate;"
  [coords]
  (into-array Coordinate (map ->coordinate coords)))

(defn- ->polygon
  "Return a geo polygon from the provided vector of vector of point vectors"
  [^GeometryFactory geo-factory coords]
  (let [linear-rings (map
                       (fn [ring-coords]
                         (.createLinearRing
                           geo-factory
                           (->coordinates ring-coords)))
                       coords)]
    (.createPolygon geo-factory (first linear-rings) (into-array LinearRing (rest linear-rings)))))

(defn- ->geo
  "Return the provided map as a geometry"
  ([m] (->geo geo-factory m))
  (^Geometry [^GeometryFactory geo-factory m]
   (when (map? m)
     (let [coords (:geometry/coordinates m)]
       (case (:geometry/type m)
         :geometry.type/point       (.createPoint geo-factory (->coordinate coords))
         :geometry.type/multi-point (.createMultiPoint geo-factory (->coordinates coords))
         :geometry.type/line-string (.createLineString geo-factory (->coordinates coords))
         :geometry.type/polygon     (->polygon geo-factory coords)
         :geometry.type/multi-polygon
         (.createMultiPolygon
           geo-factory (into-array Polygon (map (partial ->polygon geo-factory) coords)))
         nil)))))

(defn- coord->vec
  "Return a coordinate as an x y vector"
  [^Coordinate coord]
  [(.-x coord) (.-y coord)])

(defn- ->geo-map
  "Return the provided Geometry as a map"
  [^Geometry geo]
  (condp instance? geo
    Point        {:geometry/type        :geometry.type/point
                  :geometry/coordinates (coord->vec (.getCoordinate geo))}
    MultiPoint   {:geometry/type        :geometry.type/multi-point
                  :geometry/coordinates (mapv coord->vec (.getCoordinates geo))}
    LineString   {:geometry/type        :geometry.type/line-string
                  :geometry/coordinates (mapv coord->vec (.getCoordinates geo))}
    Polygon      {:geometry/type :geometry.type/polygon
                  :geometry/coordinates
                  (let [^Polygon geo geo]
                    (into [(mapv coord->vec (.. geo getExteriorRing getCoordinates))]
                          (for [n (range (.getNumInteriorRing geo))]
                            (mapv coord->vec (.. geo
                                                 (getInteriorRingN n)
                                                 (getCoordinates))))))}
    MultiPolygon {:geometry/type :geometry.type/multi-polygon
                  :geometry/coordinates
                  (vec
                    (for [n    (range (.getNumGeometries geo))
                          :let [{coords :geometry/coordinates}
                                (->geo-map (.getGeometryN geo n))]]
                      coords))}))


(defn geo?
  "Return whether the provided map `m` can be interprited as a geometry"
  [m]
  (some? (->geo m)))

(defn a->rtree
  "Atomically grab the rtree from the provided ar-tree* atom"
  ^STRtree
  [ar-tree* a]
  (swap! ar-tree* update a #(or % (STRtree.)))
  (get @ar-tree* a))

(defn ->id-str
  [a v]
  (str (cc/new-id (DocID. a v))))

(defn index!
  "Index the provided documents into the passed in r-tree"
  [{:keys [r-trees
           index-store
           ^GeometryFactory geo-factory]} docs]
  (doseq [[_id doc] docs
          [k v]     doc
          :let      [geo         (->geo geo-factory v)
                     known-attrs (set (keys @r-trees))]
          :when     geo
          :let      [r-tree    (a->rtree r-trees k)
                     new-attrs (set (keys @r-trees))]]
    (when (not= known-attrs new-attrs)
      (db/store-index-meta index-store ::known-attrs new-attrs))
    (.insert r-tree (.getEnvelopeInternal geo) geo)))

(defn evict!
  "Evict the provided document IDs"
  [{:keys [index-store
           geo-factory
           r-trees]} eids]
  (with-open [index-snapshot (db/open-index-snapshot index-store)]
    (doseq [[a v] (db/exclusive-avs index-store eids)
            :let  [a (db/decode-value index-snapshot a)
                   ^STRtree r-tree (get @r-trees a)]
            :when r-tree
            :let  [v   (db/decode-value index-snapshot v)
                   geo (->geo geo-factory v)
                   env (.getEnvelopeInternal geo)]
            :when geo]
      (doseq [^Geometry item (.query r-tree env)
              :when          (.equals item geo)]
        (.remove r-tree env item)))))


(defmethod q/pred-args-spec 'geo-nearest [_]
  (s/cat :pred-fn  #{'geo-nearest}
         :args (s/spec (s/cat :attr keyword? :v (some-fn geo? symbol?)))
         :return (s/? :crux.query/binding)))

(defmethod q/pred-constraint 'geo-nearest [_ pred-ctx]
  (let [{:keys [::geo-store
                arg-bindings
                return-type
                tuple-idxs-in-join-order
                idx-id]} pred-ctx
        attr             (second arg-bindings)
        r-tree           (a->rtree (:r-trees geo-store) attr)
        geo-fac          (:geo-factory geo-store)
        geo-dist         (GeometryItemDistance.)]
    (fn pred-get-attr-constraint [index-snapshot
                                  {:keys [entity-resolver-fn]}
                                  idx-id->idx join-keys]
      (let [[v n] (->> arg-bindings
                       (drop 2)
                       (map #(if (instance? VarBinding %)
                               (q/bound-result-for-var index-snapshot % join-keys)
                               %)))]
        (when-some [geo (->geo geo-fac v)]
          (->> (for [neighbor     (->> (.nearestNeighbour r-tree
                                                          (.getEnvelopeInternal geo)
                                                          geo
                                                          geo-dist
                                                          (inc (or n 1)))
                                       (remove #(.equals geo %))
                                       (take (or n 1)))
                     :let         [neighbor-map (->geo-map neighbor)]
                     neighbor-eid (db/ave index-snapshot attr neighbor-map nil entity-resolver-fn)]
                 [(db/decode-value index-snapshot neighbor-eid) neighbor-map])
               (q/bind-binding return-type tuple-idxs-in-join-order (get idx-id->idx idx-id))))))))


(defmethod q/pred-args-spec 'geo-intersects [_]
  (s/cat :pred-fn  #{'geo-intersects}
         :args (s/spec (s/cat :attr keyword? :v (some-fn geo? symbol?)))
         :return (s/? :crux.query/binding)))

(defmethod q/pred-constraint 'geo-intersects [_ pred-ctx]
  (let [{:keys [::geo-store
                arg-bindings
                return-type
                tuple-idxs-in-join-order
                idx-id]} pred-ctx
        attr             (second arg-bindings)
        r-tree           (a->rtree (:r-trees geo-store) attr)
        geo-fac          (:geo-factory geo-store)]
    (fn pred-get-attr-constraint [index-snapshot
                                  {:keys [entity-resolver-fn]}
                                  idx-id->idx join-keys]
      (let [[v] (->> arg-bindings
                     (drop 2)
                     (map #(if (instance? VarBinding %)
                             (q/bound-result-for-var index-snapshot % join-keys)
                             %)))]
        (when-some [geo (->geo geo-fac v)]
          (->> (for [item         (.query r-tree (.getEnvelopeInternal geo))
                     :when        (not= geo item)
                     :let         [neighbor-map (->geo-map item)]
                     neighbor-eid (db/ave index-snapshot attr neighbor-map nil entity-resolver-fn)]
                 [(db/decode-value index-snapshot neighbor-eid) neighbor-map])
               (q/bind-binding return-type tuple-idxs-in-join-order (get idx-id->idx idx-id))))))))

(defn ->geo-store
  {::sys/args   {:srid      {:doc       "Spatial Reference Identifier"
                             :required? false
                             :default   4326
                             :spec      pos-int?}
                 :precision {:doc       "The Precision to use"
                             :required? false
                             :default   :floating
                             :spec      keyword?}}
   ::sys/deps   {:bus            :crux/bus
                 :document-store :crux/document-store
                 :index-store    :crux/index-store
                 :query-engine   :crux/query-engine}
   ::sys/before #{[:crux/tx-ingester]}}
  [{:keys [document-store bus query-engine srid precision index-store]}]
  (let [ctx {:r-trees     (atom (into {}
                                      (for [attr (db/read-index-meta index-store ::known-attrs)
                                            :let [r-tree (STRtree.)]]
                                        [attr r-tree])))
             :index-store index-store
             :geo-factory (GeometryFactory.
                            (case precision
                              :floating (PrecisionModel.))
                            srid)}]
    (with-open [index-snapshot (db/open-index-snapshot index-store)]
      (doseq [attr  (keys @(:r-trees ctx))
              :let  [^STRtree r-tree (get @(:r-trees ctx) attr)]
              v     (db/av index-snapshot attr nil)
              :let  [v (db/decode-value index-snapshot v)
                     geo (->geo (:geo-factory ctx) v)]
              :when geo]
        (.insert r-tree (.getEnvelopeInternal geo) geo)))
    (q/assoc-pred-ctx! query-engine ::geo-store ctx)
    (bus/listen bus {:crux/event-types  #{:crux.tx/committing-tx :crux.tx/aborting-tx}
                     :crux.bus/executor (reify java.util.concurrent.Executor
                                          (execute [_ f]
                                            (.run f)))}
                #(do (index! ctx (db/fetch-docs document-store (:doc-ids %)))
                     (when-some [eids (not-empty (:evicting-eids %))]
                       (evict! ctx eids))))))
