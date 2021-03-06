;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.neanderthal.internal.device.clblock
  (:require [uncomplicate.commons
             [core :refer [Releaseable release let-release with-release Mappable mmap unmap
                           wrap-float wrap-double wrap-int wrap-long]]]
            [uncomplicate.fluokitten.protocols :refer [Magma Monoid Foldable Applicative]]
            [uncomplicate.clojurecl.core :refer :all]
            [uncomplicate.neanderthal
             [math :refer [ceil]]
             [core :refer [transfer! copy!]]
             [real :refer [entry]]]
            [uncomplicate.neanderthal.internal
             [api :refer :all]
             [common :refer [dense-rows dense-cols dense-dias]]
             [printing :refer [print-vector print-ge print-uplo]]
             [navigation :refer :all]]
            [uncomplicate.neanderthal.internal.host
             [fluokitten :refer [vector-op matrix-op vector-pure matrix-pure]]
             [buffer-block :refer [real-block-vector real-ge-matrix real-tr-matrix]]])
  (:import [clojure.lang IFn IFn$L IFn$LD IFn$LLD]
           [uncomplicate.clojurecl.core CLBuffer]
           [uncomplicate.neanderthal.internal.api DataAccessor DenseVector Vector RealVector Matrix
            RealMatrix GEMatrix TRMatrix RealChangeable RealOrderNavigator UploNavigator StripeNavigator
            DenseMatrix]
           [uncomplicate.neanderthal.internal.host.buffer_block RealBlockVector IntegerBlockVector
            RealGEMatrix RealTRMatrix]))

(def ^{:private true :const true} INEFFICIENT_STRIDE_MSG
  "This operation would be inefficient when stride is not 1.")

(def ^{:private true :const true} INEFFICIENT_OPERATION_MSG
  "This operation would be inefficient because it uses memory transfer. Please use transfer! of map-memory to be reminded of that.")

(defn cl-to-host [cl host]
  (let [mapped-host (mmap cl :read)]
    (try
      (copy! mapped-host host)
      (finally (unmap cl mapped-host)))))

(defn host-to-cl [host cl]
  (let [mapped-host (mmap cl :write-invalidate-region)]
    (try
      (copy! host mapped-host)
      cl
      (finally (unmap cl mapped-host)))))

(defn cl-to-obj [cl obj]
  (let [mapped-host (mmap cl :read)]
    (try
      (transfer! mapped-host obj)
      (finally (unmap cl mapped-host)))))

(defn obj-to-cl [obj cl]
  (let [mapped-host (mmap cl :write-invalidate-region)]
    (try
      (transfer! obj mapped-host)
      cl
      (finally (unmap cl mapped-host)))))

(defprotocol CLAccessor
  (get-queue [this]))

;; ================== Declarations ============================================

(declare cl-block-vector)
(declare cl-ge-matrix)
(declare cl-tr-matrix)

;; ================== Accessors ================================================

(deftype TypedCLAccessor [ctx queue et ^long w array-fn wrap-fn]
  DataAccessor
  (entryType [_]
    et)
  (entryWidth [_]
    w)
  (count [_ b]
    (quot (long (size b)) w))
  (createDataSource [_ n]
    (cl-buffer ctx (* w (max 1 (long n))) :read-write))
  (initialize [_ buf]
    (enq-fill! queue buf (array-fn 1))
    buf)
  (initialize [_ buf v]
    (enq-fill! queue buf (wrap-fn v))
    buf)
  (wrapPrim [_ s]
    (wrap-fn s))
  CLAccessor
  (get-queue [_]
    queue)
  Contextual
  (cl-context [_]
    ctx)
  DataAccessorProvider
  (data-accessor [this]
    this)
  MemoryContext
  (compatible? [this o]
    (let [da (data-accessor o)]
      (or
       (identical? this o)
       (and (instance? TypedCLAccessor da)
            (= et (.et ^TypedCLAccessor da)) (= ctx (.ctx ^TypedCLAccessor da)))
       (= ctx o)
       (= et o)))))

(defn cl-float-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Float/TYPE Float/BYTES float-array wrap-float))

(defn cl-double-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Double/TYPE Double/BYTES double-array wrap-double))

(defn cl-int-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Integer/TYPE Integer/BYTES int-array wrap-int))

(defn cl-long-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Long/TYPE Long/BYTES long-array wrap-long))

;; =============================================================================

(deftype CLBlockVector [fact ^DataAccessor da eng master buf ^long n ^long ofst ^long strd]
  Object
  (hashCode [x]
    (-> (hash :CLBlockVector) (hash-combine n) (hash-combine (nrm2 eng x))))
  (equals [x y]
    (cond
      (nil? y) false
      (identical? x y) true
      (and (instance? CLBlockVector y) (compatible? x y) (fits? x y))
      (equals-block eng x y)
      :default false))
  (toString [this]
    (format "#CLBlockVector[%s, n:%d, offset:%d stride:%d]" (.entryType da) n ofst strd))
  Releaseable
  (release [_]
    (when (compare-and-set! master true false)
      (release @buf)
      (reset! buf nil))
    true)
  Container
  (raw [_]
    (cl-block-vector fact n))
  (raw [_ fact]
    (create-vector fact n false))
  (zero [x]
    (zero x fact))
  (zero [_ fact]
    (create-vector fact n true))
  (host [x]
    (let-release [res (raw x (native-factory fact))]
      (cl-to-host x res)))
  (native [x]
    (host x))
  DenseContainer
  (view-vctr [_]
    (cl-block-vector fact false buf n ofst strd))
  (view-vctr [_ stride-mult]
    (cl-block-vector fact false buf (ceil (/ n (long stride-mult))) ofst (* (long stride-mult) strd)))
  (view-ge [_]
    (cl-ge-matrix fact false buf n 1 ofst n COLUMN_MAJOR))
  (view-ge [x stride-mult]
    (view-ge (view-ge x) stride-mult))
  (view-tr [x uplo diag]
    (view-tr (view-ge x) uplo diag))
  MemoryContext
  (fully-packed? [_]
    (= 1 strd))
  (compatible? [_ y]
    (compatible? da y))
  (fits? [_ y]
    (= n (.dim ^Vector y)))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    (native-factory fact))
  DataAccessorProvider
  (data-accessor [_]
    da)
  DenseVector
  (buffer [_]
    @buf)
  (offset [_]
    ofst)
  (stride [_]
    strd)
  (count [_]
    n)
  IFn
  (invoke [x i]
    (.entry x i))
  (invoke [x]
    n)
  IFn$LD
  (invokePrim [x i]
    (.entry x i))
  IFn$L
  (invokePrim [x]
    n)
  RealChangeable
  (set [x val]
    (set-all eng val x)
    x)
  (set [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (setBoxed [x val]
    (.set x val))
  (setBoxed [x i val]
    (.set x i val))
  (alter [_ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (alter [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  RealVector
  (dim [_]
    n)
  (entry [_ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (boxedEntry [x i]
    (.entry x i))
  (subvector [_ k l]
    (cl-block-vector fact (atom false) buf l (+ ofst (* k strd)) strd))
  Monoid
  (id [x]
    (cl-block-vector fact 0))
  Foldable
  (fold [x]
    (sum eng x))
  Mappable
  (mmap [_ flags]
    (let [host-fact (native-factory fact)
          queue (get-queue da)
          mapped-buf (enq-map-buffer! queue @buf true (* ofst (.entryWidth da))
                                      (* strd n (.entryWidth da)) flags nil nil)]
      (try
        (real-block-vector host-fact true mapped-buf n 0 strd)
        (catch Exception e (enq-unmap! queue @buf mapped-buf)))))
  (unmap [x mapped]
    (enq-unmap! (get-queue da) @buf (.buffer ^DenseVector mapped))
    x))

(defn cl-block-vector
  ([fact ^Boolean master buf-atom n ofst strd]
   (let [da (data-accessor fact)]
     (if (and (<= 0 n (.count da @buf-atom)))
       (->CLBlockVector fact da (vector-engine fact) (atom master) buf-atom n ofst strd)
       (throw (ex-info "Insufficient buffer size." {:n n :buffer-size (.count da @buf-atom)})))))
  ([fact n]
   (let-release [buf (.createDataSource (data-accessor fact) n)]
     (cl-block-vector fact true (atom buf) n 0 1))))

(extend CLBlockVector
  Applicative
  {:pure vector-pure}
  Magma
  {:op (constantly vector-op)})

(defmethod print-method CLBlockVector
  [^CLBlockVector x ^java.io.Writer w]
  (.write w (str x))
  (when (and (< 0 (.dim x)) (.buffer x))
    (let [mapped-x (mmap x :read)]
      (try
        (print-vector w mapped-x)
        (finally (unmap x mapped-x))))))

(defmethod transfer! [CLBlockVector CLBlockVector]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CLBlockVector RealBlockVector]
  [source destination]
  (cl-to-host source destination))

(defmethod transfer! [RealBlockVector CLBlockVector]
  [source destination]
  (host-to-cl source destination))

(defmethod transfer! [CLBlockVector Object]
  [source destination]
  (cl-to-obj source destination))

(defmethod transfer! [Object CLBlockVector]
  [source destination]
  (obj-to-cl source destination))

;; ================== CL Matrix ============================================

(deftype CLGEMatrix [^RealOrderNavigator navigator fact ^DataAccessor da eng master buf
                     ^long m ^long n ^long ofst ^long ld ^long sd ^long fd ^long ord]
  Object
  (hashCode [a]
    (-> (hash :CLGEMatrix) (hash-combine m) (hash-combine n)
        (hash-combine (nrm2 eng (.stripe navigator a 0)))))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? CLGEMatrix b) (compatible? a b) (fits? a b))
      (equals-block eng a b)
      :default false))
  (toString [_]
    (format "#CLGEMatrix[%s, mxn:%dx%d, order%s, offset:%d, ld:%d]"
            (.entryType da) m n (dec-property ord) ofst ld))
  Releaseable
  (release [_]
    (when (compare-and-set! master true false)
      (release @buf)
      (reset! buf nil))
    true)
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    (native-factory fact))
  DataAccessorProvider
  (data-accessor [_]
    da)
  Container
  (raw [_]
    (cl-ge-matrix fact m n ord))
  (raw [_ fact]
    (create-ge fact m n ord false))
  (zero [a]
    (zero a fact))
  (zero [_ fact]
    (create-ge fact m n ord true))
  (host [a]
    (let-release [res (raw a (native-factory fact))]
      (cl-to-host a res)))
  (native [a]
    (host a))
  DenseContainer
  (view-vctr [_]
    (if (= ld sd)
      (cl-block-vector fact false buf (* m n) ofst 1)
      (throw (ex-info "Strided GE matrix cannot be viewed as a dense vector." {:ld ld :sd sd}))))
  (view-vctr [a stride-mult]
    (view-vctr (view-vctr a) stride-mult))
  (view-ge [_]
    (cl-ge-matrix fact false buf m n ofst ld ord))
  (view-ge [_ stride-mult]
    (let [shrinked (ceil (/ fd (long stride-mult)))]
      (cl-ge-matrix fact false buf (.sd navigator sd shrinked) (.fd navigator sd shrinked)
                      ofst (* ld (long stride-mult)) ord)))
  (view-tr [_ uplo diag]
    (cl-tr-matrix fact false buf (min m n) ofst ld ord uplo diag))
  Navigable
  (order-navigator [_]
    navigator)
  MemoryContext
  (fully-packed? [_]
    (= sd ld))
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= m (.mrows ^GEMatrix b)) (= n (.ncols ^GEMatrix b))))
  (fits-navigation? [_ b]
    (= ord (.order ^DenseMatrix b)))
  GEMatrix
  (buffer [_]
    @buf)
  (offset [_]
    ofst)
  (stride [_]
    ld)
  (order [_]
    ord)
  (count [_]
    (* m n))
  (sd [_]
    sd)
  (fd [_]
    fd)
  IFn$LLD
  (invokePrim [a i j]
    (.entry a i j))
  IFn
  (invoke [a i j]
    (.entry a i j))
  (invoke [a]
    sd)
  IFn$L
  (invokePrim [a]
    sd)
  RealChangeable
  (isAllowed [a i j]
    true)
  (set [a val]
    (set-all eng val a)
    a)
  (set [_ _ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (setBoxed [a val]
    (.set a val))
  (setBoxed [a i j val]
    (.set a i j val))
  (alter [a _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (alter [a _ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  RealMatrix
  (mrows [_]
    m)
  (ncols [_]
    n)
  (entry [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (boxedEntry [a i j]
    (.entry a i j))
  (row [a i]
    (cl-block-vector fact false buf n (.index navigator ofst ld i 0) (if (= ROW_MAJOR ord) 1 ld)))
  (rows [a]
    (dense-rows a))
  (col [a j]
    (cl-block-vector fact false buf m (.index navigator ofst ld 0 j) (if (= COLUMN_MAJOR ord) 1 ld)))
  (cols [a]
    (dense-cols a))
  (dia [a]
    (cl-block-vector fact false buf (min m n) ofst (inc ld)))
  (dias [a]
    (dense-dias a))
  (submatrix [a i j k l]
    (cl-ge-matrix fact false buf k l (.index navigator ofst ld i j) ld ord))
  (transpose [a]
    (cl-ge-matrix fact false buf n m ofst ld (flip-layout ord)))
  Monoid
  (id [a]
    (cl-ge-matrix fact 0 0))
  Mappable
  (mmap [a flags]
    (let [host-fact (native-factory fact)
          queue (get-queue da)
          mapped-buf (enq-map-buffer! queue @buf true (* ofst (.entryWidth da))
                                      (* fd ld (.entryWidth da)) flags nil nil)]
      (try
        (real-ge-matrix host-fact true mapped-buf m n 0 ld ord)
        (catch Exception e (enq-unmap! queue @buf mapped-buf)))))
  (unmap [this mapped]
    (enq-unmap! (get-queue da) @buf (.buffer ^GEMatrix mapped))
    this))

(defn cl-ge-matrix
  ([fact master buf-atom m n ofst ld ord]
   (let [^RealOrderNavigator navigator (if (= COLUMN_MAJOR ord) col-navigator row-navigator)]
     (->CLGEMatrix (if (= COLUMN_MAJOR ord) col-navigator row-navigator) fact (data-accessor fact)
                   (ge-engine fact) (atom master) buf-atom m n ofst (max (long ld) (.sd navigator m n))
                   (.sd navigator m n) (.fd navigator m n) ord)))
  ([fact ^long m ^long n ord]
   (let-release [buf (.createDataSource (data-accessor fact) (* m n))]
     (cl-ge-matrix fact true (atom buf) m n 0 0 ord)))
  ([fact ^long m ^long n]
   (cl-ge-matrix fact m n DEFAULT_ORDER)))

(extend CLGEMatrix
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defmethod print-method CLGEMatrix [^CLGEMatrix a ^java.io.Writer w]
  (.write w (str a))
  (when (and (< 0 (.count a)) (.buffer a))
    (let [mapped-a (mmap a :read)]
      (try
        (print-ge w mapped-a)
        (finally (unmap a mapped-a))))))

(defmethod transfer! [CLGEMatrix CLGEMatrix]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CLGEMatrix RealGEMatrix]
  [source destination]
  (cl-to-host source destination))

(defmethod transfer! [RealGEMatrix CLGEMatrix]
  [source destination]
  (host-to-cl source destination))

(defmethod transfer! [CLGEMatrix Object]
  [source destination]
  (cl-to-obj source destination))

(defmethod transfer! [Object CLGEMatrix]
  [source destination]
  (obj-to-cl source destination))

;; ============ OpenCL Triangular Matrix =======================================

(deftype CLTRMatrix [^RealOrderNavigator navigator ^UploNavigator uplo-nav ^StripeNavigator stripe-nav
                     fact ^DataAccessor da eng master buf ^long n ^long ofst ^long ld ^long ord
                     ^long fuplo ^long fdiag]
  Object
  (hashCode [this]
    (-> (hash :CLTRMatrix) (hash-combine n) (hash-combine (nrm2 eng (.stripe navigator this 0)))))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? CLTRMatrix b) (compatible? da b) (fits? a b))
      (equals-block eng a b)
      :default false))
  (toString [a]
    (format "#CLTRMatrix[%s, mxn:%dx%d, order%s, uplo%s, diag%s, offset:%d, ld:%d]"
            (.entryType da) n n (dec-property ord) (dec-property fuplo) (dec-property fdiag) ofst ld ))
  Releaseable
  (release [_]
    (when (compare-and-set! master true false)
      (release @buf)
      (reset! buf nil))
    true)
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    (native-factory fact))
  DataAccessorProvider
  (data-accessor [_]
    da)
  Container
  (raw [_]
    (cl-tr-matrix fact n ord fuplo fdiag))
  (raw [_ fact]
    (create-tr fact n ord fuplo fdiag false))
  (zero [_]
    (zero _ fact))
  (zero [_ fact]
    (create-tr fact n ord fuplo fdiag true))
  (host [a]
    (let-release [res (raw a (native-factory fact))]
      (cl-to-host a res)))
  (native [a]
    (host a))
  DenseContainer
  (view-vctr [a]
    (view-vctr (view-ge a)))
  (view-vctr [a stride-mult]
    (view-vctr (view-ge a) stride-mult))
  (view-ge [_]
    (cl-ge-matrix fact false buf n n ofst ld ord))
  (view-ge [a stride-mult]
    (view-ge (view-ge a) stride-mult))
  (view-tr [_ uplo diag]
    (cl-tr-matrix fact false buf n ofst ld ord uplo diag))
  Navigable
  (order-navigator [_]
    navigator)
  (stripe-navigator [_]
    stripe-nav)
  (uplo-navigator [_]
    uplo-nav)
  MemoryContext
  (fully-packed? [_]
    false)
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= n (.mrows ^TRMatrix b)) (= fuplo (.uplo ^TRMatrix b)) (= fdiag (.diag ^TRMatrix b))))
  (fits-navigation? [_ b]
    (and (= ord (.order ^DenseMatrix b))
         (or (not (instance? TRMatrix b)) (= fuplo (.uplo ^TRMatrix b))) (= fdiag (.diag ^TRMatrix b))))
  Monoid
  (id [a]
    (cl-tr-matrix fact 0))
  TRMatrix
  (buffer [_]
    @buf)
  (offset [_]
    ofst)
  (stride [_]
    ld)
  (count [_]
    (* n n))
  (uplo [_]
    fuplo)
  (diag [_]
    fdiag)
  (order [_]
    ord)
  (sd [_]
    n)
  (fd [_]
    n)
  IFn$LLD
  (invokePrim [a i j]
    (.entry a i j))
  IFn
  (invoke [a i j]
    (.entry a i j))
  (invoke [a]
    n)
  IFn$L
  (invokePrim [a]
    n)
  RealChangeable
  (isAllowed [a i j]
    (= 2 (.defaultEntry uplo-nav i j)))
  (set [a val]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (set [a i j val]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (setBoxed [a val]
    (.set a val))
  (setBoxed [a i j val]
    (.set a i j val))
  (alter [a _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (alter [a _ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  RealMatrix
  (mrows [_]
    n)
  (ncols [_]
    n)
  (entry [a i j]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (boxedEntry [this i j]
    (.entry this i j))
  (row [a i]
    (let [start (.rowStart uplo-nav n i)]
      (cl-block-vector fact false buf (- (.rowEnd uplo-nav n i) start)
                       (.index navigator ofst ld i start) (if (= ROW_MAJOR ord) 1 ld))))
  (rows [a]
    (dense-rows a))
  (col [a j]
    (let [start (.colStart uplo-nav n j)]
      (cl-block-vector fact false buf (- (.colEnd uplo-nav n j) start)
                       (.index navigator ofst ld start j) (if (= COLUMN_MAJOR ord) 1 ld))))
  (cols [a]
    (dense-cols a))
  (dia [a]
    (cl-block-vector fact false buf n ofst (inc ld)))
  (dias [a]
    (dense-dias a))
  (submatrix [a i j k l]
    (if (and (= i j) (= k l))
      (cl-tr-matrix fact false buf k (.index navigator ofst ld i j) ld ord fuplo fdiag)
      (throw (ex-info "You cannot use regions outside the triangle in TR submatrix"
                      {:a (str a) :i i :j j :k k :l l}))))
  (transpose [a]
    (cl-tr-matrix fact false buf n ofst ld (flip-layout ord) (flip-uplo fuplo) fdiag))
  Mappable
  (mmap [a flags]
    (let [host-fact (native-factory fact)
          queue (get-queue da)
          mapped-buf (enq-map-buffer! queue @buf true (* ofst (.entryWidth da))
                                      (* ld n (.entryWidth da)) flags nil nil)]
      (try
        (real-tr-matrix host-fact true mapped-buf n 0 ld ord fuplo fdiag)
        (catch Exception e (enq-unmap! queue @buf mapped-buf)))))
  (unmap [this mapped]
    (enq-unmap! (get-queue da) @buf (.buffer ^TRMatrix mapped))
    this))

(extend CLTRMatrix
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defn cl-tr-matrix
  ([fact master buf-atom n ofst ld ord uplo diag]
   (let [unit (= DIAG_UNIT diag)
         lower (= LOWER uplo)
         column (= COLUMN_MAJOR ord)
         bottom (if lower column (not column))
         order-nav (if column col-navigator row-navigator)
         uplo-nav (if lower
                    (if unit unit-lower-nav non-unit-lower-nav)
                    (if unit unit-upper-nav non-unit-upper-nav))
         stripe-nav (if bottom
                      (if unit unit-bottom-navigator non-unit-bottom-navigator)
                      (if unit unit-top-navigator non-unit-top-navigator))]
     (->CLTRMatrix order-nav uplo-nav stripe-nav fact (data-accessor fact) (tr-engine fact)
                   (atom master) buf-atom n ofst (max (long ld) (long n)) ord uplo diag)))
  ([fact n ord uplo diag]
   (let-release [buf (.createDataSource (data-accessor fact) (* (long n) (long n)))]
     (cl-tr-matrix fact true (atom buf) n 0 n ord uplo diag)))
  ([fact n]
   (cl-tr-matrix fact n DEFAULT_ORDER DEFAULT_UPLO DEFAULT_DIAG)))

(defmethod print-method CLTRMatrix [^CLTRMatrix a ^java.io.Writer w]
  (.write w (str a))
  (when (and (< 0 (.count a)) (.buffer a))
    (let [mapped-a (mmap a :read)]
      (try
        (print-uplo w mapped-a)
        (finally (unmap a mapped-a))))))

(defmethod transfer! [CLTRMatrix CLTRMatrix]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CLTRMatrix RealTRMatrix]
  [source destination]
  (cl-to-host source destination))

(defmethod transfer! [RealTRMatrix CLTRMatrix]
  [source destination]
  (host-to-cl source destination))

(defmethod transfer! [CLTRMatrix Object]
  [source destination]
  (cl-to-obj source destination))

(defmethod transfer! [Object CLTRMatrix]
  [source destination]
  (obj-to-cl source destination))
