;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.neanderthal.internal.cuda.cublock
  (:require [uncomplicate.commons
             [core :refer [Releaseable release let-release with-release Mappable mmap unmap
                           wrap-float wrap-double wrap-int wrap-long]]
             [utils :refer [with-check]]]
            [uncomplicate.fluokitten.protocols :refer [Magma Monoid Foldable Applicative]]
            [uncomplicate.clojurecuda
             [protocols :refer :all]
             [core :refer :all]]
            [uncomplicate.neanderthal
             [math :refer [ceil]]
             [core :refer [transfer! copy! vctr ge]]
             [real :refer [entry]]]
            [uncomplicate.neanderthal.internal
             [api :refer :all]
             [common :refer :all]
             [navigation :refer :all]]
            [uncomplicate.neanderthal.internal.host
             [fluokitten :refer [vector-op matrix-op vector-pure matrix-pure]]
             [buffer-block :refer [real-block-vector real-ge-matrix real-tr-matrix]]])
  (:import [clojure.lang IFn IFn$L IFn$LD IFn$LLD]
           [jcuda.jcublas JCublas2 cublasStatus]
           [uncomplicate.neanderthal.internal.api DataAccessor Block Vector RealVector Matrix
            RealMatrix GEMatrix TRMatrix RealChangeable RealOrderNavigator UploNavigator StripeNavigator]
           [uncomplicate.neanderthal.internal.host.buffer_block RealBlockVector IntegerBlockVector
            RealGEMatrix RealTRMatrix]))

(def ^{:private true :const true} INEFFICIENT_STRIDE_MSG
  "This operation would be inefficient when stride is not 1.")

(def ^{:private true :const true} INEFFICIENT_OPERATION_MSG
  "This operation would be inefficient because it uses memory transfer. Please use transfer! of map-memory to be reminded of that.")

;; ================== Declarations ============================================

(declare cu-block-vector)
(declare cu-ge-matrix)
(declare cu-tr-matrix)

;; ================== Accessors ================================================

(defprotocol CUAccessor
  ;;TODO (get-stream [this])
  (offset [this buf ofst]))

(deftype TypedCUAccessor [ctx et ^long w wrap-fn]
  DataAccessor
  (entryType [_]
    et)
  (entryWidth [_]
    w)
  (count [_ b]
    (quot (long (size b)) w))
  (createDataSource [_ n]
    (mem-alloc (* w (max 1 (long n)))))
  (initialize [_ buf]
    (memset! buf 0)
    buf)
  (wrapPrim [_ s]
    (wrap-fn s))
  CUAccessor
  (offset [_ buf-ptr ofst]
    (if (= 0 (long ofst))
      buf-ptr
      (with-offset buf-ptr (* (long ofst) w))))
  DataAccessorProvider
  (data-accessor [this]
    this)
  Contextual
  (cu-context [_]
    ctx)
  MemoryContext
  (compatible? [this o]
    (let [da (data-accessor o)]
      (or
       (identical? this o)
       (and (instance? TypedCUAccessor da)
            (= et (.et ^TypedCUAccessor da)) (= ctx (.ctx ^TypedCUAccessor da)))
       (= ctx o)
       (= et o)))))

(defn cu-float-accessor [ctx]
  (->TypedCUAccessor ctx Float/TYPE Float/BYTES wrap-float))

(defn cu-double-accessor [ctx]
  (->TypedCUAccessor ctx Double/TYPE Double/BYTES wrap-double))

(defn cu-int-accessor [ctx]
  (->TypedCUAccessor ctx Integer/TYPE Integer/BYTES wrap-int))

(defn cu-long-accessor [ctx]
  (->TypedCUAccessor ctx Long/TYPE Long/BYTES wrap-long))

;; ================ CUDA memory transfer ======================================

(defn cublas-error [^long err-code details]
  (let [err (cublasStatus/stringFor err-code)]
    (ex-info (format "cuBLAS error: %s." err)
             {:name err :code err-code :type :cublas-error :details details})))

(defn get-vector! [^Block cu ^Block host]
  (let [da (data-accessor cu)
        width (.entryWidth da)]
    (if (and (fits? cu host) (= width (.entryWidth (data-accessor host))))
      (with-check cublas-error
        (JCublas2/cublasGetVector (.dim ^Vector cu) width
                                  (offset da (cu-ptr (.buffer cu)) (.offset cu)) (.stride cu)
                                  (offset da (ptr (.buffer host)) (.offset host)) (.stride host))
        host)
      (throw (ex-info "You cannot get  incompatible or ill-fitting vector."
                      {:cu (str cu) :host (str host)})))))

(defn set-vector! [^Block host ^Block cu]
  (let [da (data-accessor cu)
        width (.entryWidth da)]
    (if (and (fits? cu host) (= width (.entryWidth (data-accessor host))))
      (with-check cublas-error
        (JCublas2/cublasSetVector (.dim ^Vector cu) width
                                  (offset da (ptr (.buffer host)) (.offset host)) (.stride host)
                                  (offset da (cu-ptr (.buffer cu)) (.offset cu)) (.stride cu))
        cu)
      (throw (ex-info "You cannot set incompatible or ill-fitting vector."
                      {:cu (str cu) :host (str host)})))))

(defn get-matrix! [^Block cu ^Block host]
  (let [da (data-accessor cu)
        width (.entryWidth da)]
    (if (and (fits? cu host) (= width (.entryWidth (data-accessor host))))
      (with-check cublas-error
        (JCublas2/cublasGetMatrix (.mrows ^Matrix cu) (.ncols ^Matrix cu) width
                                  (offset da (cu-ptr (.buffer cu)) (.offset cu)) (.stride cu)
                                  (offset da (ptr (.buffer host)) (.offset host)) (.stride host))
        host)
      (throw (ex-info "You cannot get incompatible or ill-fitting vector."
                      {:cu (str cu) :host (str host)})))))

(defn set-matrix! [^Block host ^Block cu]
  (let [da (data-accessor cu)
        width (.entryWidth da)]
    (if (and (fits? cu host) (= width (.entryWidth (data-accessor host))))
      (with-check cublas-error
        (JCublas2/cublasSetMatrix (.mrows ^Matrix cu) (.ncols ^Matrix cu) width
                                  (offset da (ptr (.buffer host)) (.offset host)) (.stride host)
                                  (offset da (cu-ptr (.buffer cu)) (.offset cu)) (.stride cu))
        cu)
      (throw (ex-info "You cannot set incompatible or ill-fitting vector."
                      {:cu (str cu) :host (str host)})))))

(defprotocol BlockEngine
  (equals-block [_ cu-x cu-y]))

;; =============================================================================

(deftype CUBlockVector [^uncomplicate.neanderthal.internal.api.Factory fact
                        ^DataAccessor da eng master buf ^long n ^long ofst ^long strd]
  Object
  (hashCode [x]
    (-> (hash :CUBlockVector) (hash-combine n) (hash-combine (nrm2 eng x))))
  (equals [x y]
    (cond
      (nil? y) false
      (identical? x y) true
      (and (instance? CUBlockVector y) (compatible? x y) (fits? x y))
      (equals-block eng x y)
      :default false))
  (toString [this]
    (format "#CUBlockVector[%s, n:%d, offset:%d stride:%d]" (.entryType da) n ofst strd))
  Releaseable
  (release [_]
    (when (compare-and-set! master true false)
      (release @buf))
    (reset! buf nil)
    true)
  Container
  (raw [_]
    (cu-block-vector fact n))
  (raw [_ fact]
    (create-vector fact n false))
  (zero [x]
    (zero x fact))
  (zero [_ fact]
    (create-vector fact n true))
  (host [x]
    (let-release [res (raw x (native-factory fact))]
      (get-vector! x res)))
  (native [x]
    (host x))
  DenseContainer
  (view-vctr [_]
    (cu-block-vector fact false @buf n ofst strd))
  (view-vctr [_ stride-mult]
    (cu-block-vector fact false @buf (ceil (/ n (long stride-mult))) ofst (* (long stride-mult) strd)))
  (view-ge [_]
    (cu-ge-matrix fact false @buf n 1 ofst n COLUMN_MAJOR))
  (view-ge [x stride-mult]
    (view-ge (view-ge x) stride-mult))
  (view-tr [x uplo diag]
    (view-tr (view-ge x) uplo diag))
  MemoryContext
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
  Block
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
    (cu-block-vector fact (atom false) @buf l (+ ofst (* k strd)) strd))
  Monoid
  (id [x]
    (cu-block-vector fact 0))
  Foldable
  (fold [x]
    (sum eng x)))

(defn cu-block-vector
  ([fact ^Boolean master buf n ofst strd]
   (let [da (data-accessor fact)]
     (if (and (<= 0 n (.count da buf)))
       (->CUBlockVector fact da (vector-engine fact) (atom master) (atom buf) n ofst strd)
       (throw (ex-info "Insufficient buffer size." {:n n :buffer-size (.count da buf)})))))
  ([fact n]
   (let-release [buf (.createDataSource (data-accessor fact) n)]
     (cu-block-vector fact true buf n 0 1))))

(extend CUBlockVector
  Applicative
  {:pure vector-pure}
  Magma
  {:op (constantly vector-op)})

(defmethod print-method CUBlockVector
  [^CUBlockVector x ^java.io.Writer w]
  (if (and (< 0 (.dim x)) (.buffer x))
    (let-release [host-x (host x)]
      (.write w (str x "\n["))
      (let [max-value (double (amax (engine host-x) host-x))
            min-value (entry host-x (iamin (engine host-x) host-x))
            formatter (if (and (not (< 0.0 min-value 0.01)) (< max-value 10000.0)) format-f format-g)]
        (format-vector w formatter host-x))
      (.write w "]"))
    (.write w (str x))))

(defmethod transfer! [CUBlockVector CUBlockVector]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CUBlockVector RealBlockVector]
  [source destination]
  (get-vector! source destination))

(defmethod transfer! [RealBlockVector CUBlockVector]
  [source destination]
  (set-vector! source destination))

(defmethod transfer! [CUBlockVector Object]
  [source destination]
  (with-release [h (host source)]
    (transfer! h destination)))

(defmethod transfer! [Object CUBlockVector]
  [source destination]
  (with-release [h (vctr (native-factory destination) source)]
    (set-vector! h destination)))

;; ================== CUDA Matrix ============================================

(deftype CUGEMatrix [^RealOrderNavigator navigator ^uncomplicate.neanderthal.internal.api.Factory fact
                     ^DataAccessor da eng master buf ^long m ^long n
                     ^long ofst ^long ld ^long sd ^long fd ^long ord]
  Object
  (hashCode [a]
    (-> (hash :CUGEMatrix) (hash-combine m) (hash-combine n)
        (hash-combine (nrm2 eng (.stripe navigator a 0)))))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? CUGEMatrix b) (compatible? a b) (fits? a b))
      (equals-block eng a b)
      :default false))
  (toString [_]
    (format "#CUGEMatrix[%s, mxn:%dx%d, order%s, offset:%d, ld:%d]"
            (.entryType da) m n (dec-property ord) ofst ld))
  Releaseable
  (release [_]
    (when (compare-and-set! master true false)
      (release @buf))
    (reset! buf nil)
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
    (cu-ge-matrix fact m n ord))
  (raw [_ fact]
    (create-ge fact m n ord false))
  (zero [a]
    (zero a fact))
  (zero [_ fact]
    (create-ge fact m n ord true))
  (host [a]
    (let-release [res (raw a (native-factory fact))]
      (get-matrix! a res)))
  (native [a]
    (host a))
  DenseContainer
  (view-vctr [_]
    (if (= ld sd)
      (cu-block-vector fact false @buf (* m n) ofst 1)
      (throw (ex-info "Strided GE matrix cannot be viewed as a dense vector." {:ld ld :sd sd}))))
  (view-vctr [a stride-mult]
    (view-vctr (view-vctr a) stride-mult))
  (view-ge [_]
    (cu-ge-matrix fact false @buf m n ofst ld ord))
  (view-ge [_ stride-mult]
    (let [shrinked (ceil (/ fd (long stride-mult)))]
      (cu-ge-matrix fact false @buf (.sd navigator sd shrinked) (.fd navigator sd shrinked)
                    ofst (* ld (long stride-mult)) ord)))
  (view-tr [_ uplo diag]
    (cu-tr-matrix fact false @buf (min m n) ofst ld ord uplo diag))
  MemoryContext
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= m (.mrows ^GEMatrix b)) (= n (.ncols ^GEMatrix b))))
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
    (cu-block-vector fact false @buf n (.index navigator ofst ld i 0) (if (= ROW_MAJOR ord) 1 ld)))
  (col [a j]
    (cu-block-vector fact false @buf m (.index navigator ofst ld 0 j) (if (= COLUMN_MAJOR ord) 1 ld)))
  (dia [a]
    (cu-block-vector fact false @buf (min m n) ofst (inc ld)))
  (submatrix [a i j k l]
    (cu-ge-matrix fact false @buf k l (.index navigator ofst ld i j) ld ord))
  (transpose [a]
    (cu-ge-matrix fact false @buf n m ofst ld (if (= COLUMN_MAJOR ord) ROW_MAJOR COLUMN_MAJOR)))
  Monoid
  (id [a]
    (cu-ge-matrix fact 0 0)))

(defn cu-ge-matrix
  ([fact master buf m n ofst ld ord]
   (let [^RealOrderNavigator navigator (if (= COLUMN_MAJOR ord) col-navigator row-navigator)]
     (->CUGEMatrix (if (= COLUMN_MAJOR ord) col-navigator row-navigator) fact (data-accessor fact)
                   (ge-engine fact) (atom master) (atom buf) m n ofst (max (long ld) (.sd navigator m n))
                   (.sd navigator m n) (.fd navigator m n) ord)))
  ([fact ^long m ^long n ord]
   (let-release [buf (.createDataSource (data-accessor fact) (* m n))]
     (cu-ge-matrix fact true buf m n 0 0 ord)))
  ([fact ^long m ^long n]
   (cu-ge-matrix fact m n DEFAULT_ORDER)))

(extend CUGEMatrix
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defmethod print-method CUGEMatrix [^CUGEMatrix a ^java.io.Writer w]
  (if (and (< 0 (.count a)) (.buffer a))
    (let-release [host-a (host a)]
      (.write w (str a "\n"))
      (let [max-value (double (amax (engine host-a) host-a))
            formatter (if (< max-value 10000.0) format-f format-g)]
        (format-matrix w formatter host-a max-value))
      (.write w "\n"))
    (.write w (str a))))

(defmethod transfer! [CUGEMatrix CUGEMatrix]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CUGEMatrix RealGEMatrix]
  [source destination]
  (get-matrix! source destination))

(defmethod transfer! [RealGEMatrix CUGEMatrix]
  [source destination]
  (set-matrix! source destination))

(defmethod transfer! [CUGEMatrix Object]
  [source destination]
  (with-release [h (host source)]
    (transfer! h destination)))

(defmethod transfer! [Object CUGEMatrix]
  [source destination]
  (with-release [h (raw destination (native-factory destination))]
    (set-matrix! (transfer! source h) destination)))
