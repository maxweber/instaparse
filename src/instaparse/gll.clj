(ns instaparse.gll
  (:require [instaparse.incremental-vector :as iv])
  (:use clojure.pprint clojure.repl))

(def DEBUG nil)
(defmacro debug [& body]
  (when DEBUG
    `(do ~@body)))
(defmacro dprintln [& body]
  `(debug (println ~@body)))

;TODO
;    ENBF
;Error messages
;Documentation
;Move parse calls into listener creation for clarity
;Incremental Hashing
;Concurrency
;Allow parsing of arbitrary sequences.


(def stats (atom {}))
(defn add! [call] (swap! stats update-in [call] (fnil inc 0)))
(defn clear! [] (reset! stats {}))

(defn get-parser [grammar p]
  (get grammar p p))

(declare alt-parse cat-parse string-parse epsilon-parse non-terminal-parse
         opt-parse plus-parse star-parse regexp-parse lookahead-parse
         negative-lookahead-parse ordered-alt-parse)
(defn -parse [parser index tramp]
  (dprintln "-parse" index (:tag parser))
  (case (:tag parser)
    :nt (non-terminal-parse parser index tramp)
    :alt (alt-parse parser index tramp)
    :cat (cat-parse parser index tramp)
    :string (string-parse parser index tramp)
    :epsilon (epsilon-parse index tramp)
    :opt (opt-parse parser index tramp)
    :plus (plus-parse parser index tramp)
    :star (star-parse parser index tramp)
    :regexp (regexp-parse parser index tramp)
    :look (lookahead-parse parser index tramp)
    :neg (negative-lookahead-parse parser index tramp)
    :ord (ordered-alt-parse parser index tramp)))

(declare alt-full-parse cat-full-parse string-full-parse epsilon-full-parse 
         non-terminal-full-parse opt-full-parse plus-full-parse star-full-parse
         regexp-full-parse lookahead-full-parse ordered-alt-full-parse)
(defn -full-parse [parser index tramp]
  (dprintln "-full-parse" index (:tag parser))
  (case (:tag parser)
    :nt (non-terminal-full-parse parser index tramp)
    :alt (alt-full-parse parser index tramp)
    :cat (cat-full-parse parser index tramp)
    :string (string-full-parse parser index tramp)
    :epsilon (epsilon-full-parse index tramp)
    :opt (opt-full-parse parser index tramp)
    :plus (plus-full-parse parser index tramp)
    :star (star-full-parse parser index tramp)
    :regexp (regexp-full-parse parser index tramp)
    :look (lookahead-full-parse parser index tramp)
    :neg (negative-lookahead-parse parser index tramp)
    :ord (ordered-alt-full-parse parser index tramp)))

; The trampoline structure contains the grammar, text to parse, a stack and a nodes
; Also contains an atom to hold successes and one to hold index of failure point.
; grammar is a map from non-terminals to parsers
; text is a string
; stack is an atom of a vector containing items implementing the Execute protocol.
; nodes is an atom containing a map from [index parser] pairs to Nodes
; success contains a successful parse
; failure contains the index of the furthest-along failure

(defrecord Tramp [grammar text stack next-stack generation 
                  failure-listeners msg-cache nodes success failure])
(defn make-tramp [grammar text] 
  (Tramp. grammar text (atom []) (atom []) (atom 0) (atom []) 
          (atom {}) (atom {}) (atom nil) (atom 0)))
  
; A Success record contains the result and the index to continue from
(defn make-success [result index] {:result result :index index})
(defn total-success? [tramp s]
  (= (count (:text tramp)) (:index s)))

; The trampoline's nodes field is map from [index parser] pairs to Nodes
; Nodes track the results of a given parser at a given index, and the listeners
; who care about the result.
; results are expected to be refs of sets.
; listeners are refs of vectors.

(defrecord Node [listeners full-listeners results full-results])
(defn make-node [] (Node. (atom []) (atom []) (atom #{}) (atom #{})))
; Currently using records for Node.  Seems to run marginally faster.
; Here's the way without records:
;(defn make-node [] {:listeners (atom []) :full-listeners (atom []) 
;                    :results (atom #{}) :full-results (atom #{})})

;; Trampoline helper functions

(defn push-stack
  "Pushes an item onto the trampoline's stack"
  [tramp item]
  (add! :push-stack)
  (swap! (:stack tramp) conj item))

(defn push-message
  "Pushes onto stack a message to a given listener about a result"
  [tramp listener result]
  (let [cache (:msg-cache tramp)
        i (:index result)
        k [listener i]
        c (get @cache k 0)
        f #(listener result)]    
    #_(dprintln "push-message" i c @(:generation tramp) (count @(:stack tramp))
             (count @(:next-stack tramp)))
    #_(dprintln "listener result" listener result)
    (if (> c @(:generation tramp))
      (swap! (:next-stack tramp) conj f)
      (swap! (:stack tramp) conj f))
    (swap! cache assoc k (inc c))))
    
(defn listener-exists?
  "Tests whether node already has a listener"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (pos? (count @(:listeners node))))))

(defn full-listener-exists?
  "Tests whether node already has a listener or full-listener"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (or (pos? (count @(:full-listeners node)))
          (pos? (count @(:listeners node)))))))

(defn result-exists?
  "Tests whether node has a result or full-result"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (or (pos? (count @(:full-results node)))
          (pos? (count @(:results node)))))))

(defn full-result-exists?
  "Tests whether node has a full-result"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (pos? (count @(:full-results node))))))      

(defn node-get
  "Gets node if already exists, otherwise creates one"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (if-let [node (@nodes node-key)]
      node 
      (let [node (make-node)]
        (add! :create-node)
        (swap! nodes assoc node-key node)
        node))))

(declare apply-reduction)

(defn make-flattenable [s]
  (with-meta s {:flattenable? true}))

(defn push-result
  "Pushes a result into the trampoline's node.
   Categorizes as either result or full-result.
   Schedules notification to all existing listeners of result
   (Full listeners only get notified about full results)"
  [tramp node-key result]
  (dprintln "Push result" (node-key 0) (:tag (node-key 1)) result)
  (let [node (node-get tramp node-key)
        parser (node-key 1)
        ;; reduce result with reduction function if it exists
        result (if (:hide parser)
                 (assoc result :result nil)
                 result)
        result (if-let [reduction-function (:red parser)]
                 (assoc result :result 
                        (apply-reduction reduction-function
                                         (:result result)))
                 result)              
        total? (total-success? tramp result)
        results (if total? (:full-results node) (:results node))]
    (when (not (@results result))  ; when result is not already in @results
      (add! :push-result)
      (swap! results conj result)
      (doseq [listener @(:listeners node)]
        (push-message tramp listener result))
      (when total?
        (doseq [listener @(:full-listeners node)]
          (push-message tramp listener result)))))) 

(defn push-listener
  "Pushes a listener into the trampoline's node.
   Schedules notification to listener of all existing results.
   Initiates parse if necessary"
  [tramp node-key listener]
  (let [listener-already-exists? (listener-exists? tramp node-key)
        node (node-get tramp node-key)
        listeners (:listeners node)]
    (add! :push-listener)
    (swap! listeners conj listener)
    (doseq [result @(:results node)]
      (push-message tramp listener result))
    (doseq [result @(:full-results node)]
      (push-message tramp listener result))
    (when (not listener-already-exists?)
      (push-stack tramp #(-parse (node-key 1) (node-key 0) tramp))))) 

(defn push-full-listener
  "Pushes a listener into the trampoline's node.
   Schedules notification to listener of all existing full results."
  [tramp node-key listener]
  (let [full-listener-already-exists? (full-listener-exists? tramp node-key)
        node (node-get tramp node-key)
        listeners (:full-listeners node)]
    (add! :push-full-listener)
    (swap! listeners conj listener)
    (doseq [result @(:full-results node)]
      (push-message tramp listener result))
    (when (not full-listener-already-exists?)
      (push-stack tramp #(-full-parse (node-key 1) (node-key 0) tramp)))))

(defn push-negative-listener
  "Pushes a thunk onto the trampoline's failure-listener stack."
  [tramp negative-listener]
  (swap! (:failure-listeners tramp) conj negative-listener))  

;(defn success [tramp node-key result end]
;  (push-result tramp node-key (make-success result end)))

(defmacro success [tramp node-key result end]
  `(push-result ~tramp ~node-key (make-success ~result ~end)))


(defn fail [tramp index]  
  (swap! (:failure tramp) (fn [i] (max i index)))) 

;; Stack helper functions

;(defn step
;  "Executes one thing on the stack"
;  [stack]
;  ; It's a little tricky to atomically pop an item off of an atom-based stack
;  ; We need to get down-and-dirty with the low-level compare-and-set! function.
;  ; If step ends up being used from only one thread, this can be simplified.
;  (let [current-stack @stack
;        top (peek current-stack)
;        new-stack (pop current-stack)]
;    (if (and top (compare-and-set! stack current-stack new-stack))
;      (execute top)
;      (recur))))

(defn step
  "Executes one thing on the stack (not threadsafe)"
  [stack]
  (let [top (peek @stack)]
    (swap! stack pop)
    #_(dprintln "Top" top (meta top))
    (top)))

(defn run
  "Executes the stack until exhausted"
  ([tramp] (run tramp nil))
  ([tramp found-result?] 
    (let [stack (:stack tramp)]
      ;_ (dprintln found-result? (count @(:stack tramp)) (count @(:next-stack tramp)))
      (cond
        @(:success tramp)
        (lazy-seq (cons (:result @(:success tramp))
                        (do (reset! (:success tramp) nil)
                          (run tramp true))))
        
        (pos? (count @stack))
        (do (dprintln "stacks" (count @stack) (count @(:next-stack tramp)))
          (step stack) (recur tramp found-result?))
        
        (pos? (count @(:failure-listeners tramp)))
        (do (doseq [listener @(:failure-listeners tramp)]
              (listener))        
          (reset! (:failure-listeners tramp) [])
          (recur tramp found-result?))
        
        found-result?
        (let [next-stack (:next-stack tramp)]
          (dprintln "Swapping stacks" (count @(:stack tramp)) 
                   (count @(:next-stack tramp)))
          (reset! stack @next-stack) 
          (reset! next-stack [])
          (swap! (:generation tramp) inc)  
          (dprintln "Swapped stacks" (count @(:stack tramp)) 
                   (count @(:next-stack tramp)))          
          (recur tramp nil))        
      
        :else nil))))

;; Listeners

; There are five kinds of listeners that receive notifications
; The first kind is a NodeListener which simply listens for a completed parse result
; Takes the node-key of the parser which is awaiting this result.

(defn NodeListener [node-key tramp]  
  (fn [result]
    (dprintln "Listener" [(node-key 0) (:tag (node-key 1))] "result" result)
    (push-result tramp node-key result)))

; The second kind of listener handles lookahead.
(defn LookListener [node-key tramp]
  (fn [result]
    (success tramp node-key nil (node-key 0))))     

; The third kind of listener is a CatListener which listens at each stage of the
; concatenation parser to carry on the next step.  Think of it as a parse continuation.
; A CatListener needs to know the sequence of results for the parsers that have come
; before, and a list of parsers that remain.  Also, the node-key of the final node
; that needs to know the overall result of the cat parser.

(defn CatListener [results-so-far parser-sequence node-key tramp]
;  (pprint {:tag :CatListener
;           :results-so-far results-so-far
;           :parser-sequence (map :tag parser-sequence)
;           :node-key [(node-key 0) (:tag (node-key 1))]})
  (fn [result] 
    (let [{parsed-result :result continue-index :index} result
          new-results-so-far (conj results-so-far parsed-result)]
      (if (seq parser-sequence)
        (push-listener tramp [continue-index (first parser-sequence)]
                       (CatListener new-results-so-far (next parser-sequence) node-key tramp))          
        (success tramp node-key new-results-so-far continue-index)))))

(defn singleton? [s]
  (and (seq s) (not (next s))))

(defn CatFullListener [results-so-far parser-sequence node-key tramp]
;  (pprint {:tag :CatFullListener
;           :results-so-far results-so-far
;           :parser-sequence (map :tag parser-sequence)
;           :node-key [(node-key 0) (:tag (node-key 1))]})
  (fn [result] 
    (let [{parsed-result :result continue-index :index} result
          new-results-so-far (conj results-so-far parsed-result)]
      (cond
        (singleton? parser-sequence)
        (push-full-listener tramp [continue-index (first parser-sequence)]
                            (CatFullListener new-results-so-far (next parser-sequence) node-key tramp))        
        
        (seq parser-sequence)
        (push-listener tramp [continue-index (first parser-sequence)]
                       (CatFullListener new-results-so-far (next parser-sequence) node-key tramp))          
        
        :else
        (success tramp node-key new-results-so-far continue-index)))))

; The fourth kind of listener is a PlusListener, which is a variation of
; the CatListener but optimized for "one or more" parsers.

(defn PlusListener [results-so-far parser prev-index node-key tramp]
  (fn [result]
    (let [{parsed-result :result continue-index :index} result]
      (when (> continue-index prev-index)
        ;(dprintln "PLUS" (type results-so-far))
        (let [new-results-so-far (conj results-so-far parsed-result)]
          (push-listener tramp [continue-index parser]
                         (PlusListener new-results-so-far parser continue-index
                                       node-key tramp))            
          (success tramp node-key new-results-so-far continue-index))))))

(defn PlusFullListener [results-so-far parser prev-index node-key tramp]
  (fn [result]
    (let [{parsed-result :result continue-index :index} result]
      (when (> continue-index prev-index)
        ;(dprintln "plusfull" (type parsed-result))
        (let [new-results-so-far (conj results-so-far parsed-result)]
          (if (= continue-index (count (:text tramp)))
            (success tramp node-key new-results-so-far continue-index)
            (push-listener tramp [continue-index parser]
                           (PlusFullListener new-results-so-far parser continue-index 
                                             node-key tramp))))))))
                        
; The top level listener is the final kind of listener

(defn TopListener [tramp] 
  (fn [result] 
    (reset! (:success tramp) result)))

;; Parsers

(defn string-parse
  [this index tramp]
  (let [string (:string this)
        text (:text tramp)
        end (min (count text) (+ index (count string)))
        head (subs text index end)]      
    (if (= string head)
      (success tramp [index this] string end)
      (fail tramp index))))

(defn string-full-parse
  [this index tramp]
  (let [string (:string this)
        text (:text tramp)
        end (min (count text) (+ index (count string)))
        head (subs text index end)]      
    (if (and (= end (count text)) (= string head))
      (success tramp [index this] string end)
      (fail tramp index))))

(defn re-seq-no-submatches [regexp text]
  (for [match (re-seq regexp text)]
    (if (vector? match) (match 0) match)))

(defn regexp-parse
  [this index tramp]
  (let [regexp (:regexp this)
        text (:text tramp)
        matches (re-seq-no-submatches regexp (subs text index))]
    (if (seq matches)
      (doseq [match matches]
        (success tramp [index this] match (+ index (count match))))
      (fail tramp index))))

(defn regexp-full-parse
  [this index tramp]
  (let [regexp (:regexp this)
        text (:text tramp)
        matches (re-seq-no-submatches regexp (subs text index))
        desired-length (- (count text) index)
        filtered-matches (filter #(= (count %) desired-length) matches)]
    (if-let [seq-filtered-matches (seq filtered-matches)]
      (doseq [match seq-filtered-matches]
        (success tramp [index this] match (count text)))
      (fail tramp index))))
        
(let [empty-cat-result (make-flattenable iv/EMPTY)]
	(defn cat-parse
	  [this index tramp]
	  (let [parsers (:parsers this)]
	    ; Kick-off the first parser, with a CatListener ready to pass the result on in the chain
	    ; and with a final target of notifying this parser when the whole sequence is complete
	    (push-listener tramp [index (first parsers)] 
                    (CatListener empty-cat-result (next parsers) [index this] tramp))))	      
	
	(defn cat-full-parse
	  [this index tramp]
	  (let [parsers (:parsers this)]
	    ; Kick-off the first parser, with a CatListener ready to pass the result on in the chain
	    ; and with a final target of notifying this parser when the whole sequence is complete
	    (push-listener tramp [index (first parsers)] 
                    (CatFullListener empty-cat-result (next parsers) [index this] tramp))))	      
 
 (defn plus-parse
	  [this index tramp]
	  (let [parser (:parser this)]
	    (push-listener tramp [index parser] 
                    (PlusListener empty-cat-result parser index [index this] tramp))))       
 
 (defn plus-full-parse
   [this index tramp]
   (let [parser (:parser this)]
     (push-listener tramp [index parser] 
                    (PlusFullListener empty-cat-result parser index [index this] tramp))))       
 
 (defn star-parse
	  [this index tramp]
	  (let [parser (:parser this)]
	    (push-listener tramp [index parser] 
                    (PlusListener empty-cat-result parser index [index this] tramp))              
     (success tramp [index this] nil index)))

 (defn star-full-parse
   [this index tramp]
   (let [parser (:parser this)]
     (if (= index (count (:text tramp)))
       (success tramp [index this] nil index)
       (do
         (push-listener tramp [index parser] 
                        (PlusFullListener empty-cat-result parser index [index this] tramp))))))         
 )

(defn alt-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    (doseq [parser parsers]
      (push-listener tramp [index parser] (NodeListener [index this] tramp)))))      

(defn alt-full-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    (doseq [parser parsers]
      (push-full-listener tramp [index parser] (NodeListener [index this] tramp)))))        

(defn ordered-alt-parse
  [this index tramp]
  (let [parser1 (:parser1 this)
        parser2 (:parser2 this)
        node-key-parser1 [index parser1]
        node-key-parser2 [index parser2]
        listener (NodeListener [index this] tramp)]
    (push-listener tramp node-key-parser1 listener)
    ; If parser1 already has a result, we won't ever need to bother with parser2
    (when (not (result-exists? tramp node-key-parser1))
      (push-negative-listener 
        tramp       
        #(when (not (result-exists? tramp node-key-parser1))
           (push-listener tramp node-key-parser2 listener))))))
          
(defn ordered-alt-full-parse
  [this index tramp]
  (let [parser1 (:parser1 this)
        parser2 (:parser2 this)
        node-key-parser1 [index parser1]
        node-key-parser2 [index parser2]
        listener (NodeListener [index this] tramp)]
    (push-full-listener tramp node-key-parser1 listener)
    ; Also kick off a regular parse of parser1 to determine negative lookahead.
    (push-stack tramp #(-parse parser1 index tramp)
    ; If parser1 already has a result, we won't ever need to bother with parser2
    (when (not (result-exists? tramp node-key-parser1))
      (push-negative-listener 
        tramp       
        #(when (not (result-exists? tramp node-key-parser1))
           (push-full-listener tramp node-key-parser2 listener))))))
  
(defn opt-parse
  [this index tramp]
  (let [parser (:parser this)]
    (push-listener tramp [index parser] (NodeListener [index this] tramp))      
    (success tramp [index this] nil index)))

(defn opt-full-parse
  [this index tramp]
  (let [parser (:parser this)]
    (push-full-listener tramp [index parser] (NodeListener [index this] tramp))    
    (if (= index (count (:text tramp)))
      (success tramp [index this] nil index)
      (fail tramp index))))    

(defn non-terminal-parse
  [this index tramp]
  (let [parser (get-parser (:grammar tramp) (:keyword this))]
    (push-listener tramp [index parser] (NodeListener [index this] tramp))))      

(defn non-terminal-full-parse
  [this index tramp]
  (let [parser (get-parser (:grammar tramp) (:keyword this))]
    (push-full-listener tramp [index parser] (NodeListener [index this] tramp))))      

(defn lookahead-parse
  [this index tramp]
  (let [parser (:parser this)]
    (push-listener tramp [index parser] (LookListener [index this] tramp))))      

(defn lookahead-full-parse
  [this index tramp]
  (if (= index (count (:text tramp)))
    (lookahead-parse this index tramp)
    (fail tramp index)))

;(declare negative-parse?)
;(defn negative-lookahead-parse
;  [this index tramp]
;  (let [parser (:parser this)
;        remaining-text (subs (:text tramp) index)]
;    (if (negative-parse? (:grammar tramp) parser remaining-text)
;      (success tramp [index this] nil index)
;      (fail tramp index))))

(defn negative-lookahead-parse
  [this index tramp]
  (let [parser (:parser this)        
        node-key [index parser]]
    (if (result-exists? tramp node-key)
      (fail tramp index)
      (do 
        (push-listener tramp node-key 
                       (let [fail-send (delay (fail tramp index))]
                         (fn [result] (force fail-send))))     
        (push-negative-listener 
          tramp
          #(when (not (result-exists? tramp node-key))
             (success tramp [index this] nil index)))))))      

(def Epsilon {:tag :epsilon})
(defn epsilon-parse
  [index tramp] (success tramp [index Epsilon] nil index))
(defn epsilon-full-parse
  [index tramp] 
  (if (= index (count (:text tramp)))
    (success tramp [index Epsilon] nil index)
    (fail tramp index)))
    
;; Ways to build parsers

(defn red [parser f] (assoc parser :red f))

(defn hide [parser] (assoc parser :hide true))

(defn opt [parser] 
  (if (= parser Epsilon) Epsilon
    {:tag :opt :parser parser}))

(defn plus [parser]
  (if (= parser Epsilon) Epsilon
    {:tag :plus :parser parser}))

(defn star [parser] 
  (if (= parser Epsilon) Epsilon
    {:tag :star :parser parser}))

(defn alt [& parsers] 
  (cond
    (every? (partial = Epsilon) parsers) Epsilon
    (singleton? parsers) (first parsers)
    :else {:tag :alt :parsers parsers}))

(declare neg)
(defn ord2 [parser1 parser2]
  (cond
    (= parser1 Epsilon) Epsilon
    (= parser2 Epsilon) parser1
    :else
    ;(alt parser1 (cat (neg parser1) parser2))))
    {:tag :ord :parser1 parser1 :parser2 parser2}))

(defn ord [& parsers]
  (if (seq parsers)
    (ord2 (first parsers) (apply ord (rest parsers)))
    Epsilon))

(defn cat [& parsers]
  (if (every? (partial = Epsilon) parsers) Epsilon
    (let [parsers (remove #{Epsilon} parsers)]
      (if (singleton? parsers) (first parsers) ; apply vector reduction
        {:tag :cat :parsers parsers}))))

(defn string [s] 
  (if (= s "") Epsilon
    {:tag :string :string s}))

(defn regexp [r]
  (let [s (str \^ r)]
    (if (= s "^") Epsilon
      {:tag :regexp :regexp (re-pattern s)})))

(defn nt [s] {:tag :nt :keyword s})

(defn look [parser] {:tag :look :parser parser}) 

(defn neg [parser] {:tag :neg :parser parser})

;; Flattening and reductions

(defn flattenable? [s]
  (:flattenable? (meta s)))

(defn nt-flatten [s]
  (when (seq s)
    (let [fs (first s)]
      (cond 
        (nil? fs)         (recur (next s))
        (flattenable? fs) (concat (nt-flatten fs) (nt-flatten (next s)))
        :else             (lazy-seq (cons fs (nt-flatten (next s))))))))

(defn apply-reduction [f result]
  (apply f (nt-flatten (make-flattenable [result]))))

(defn hiccup-non-terminal-reduction [key] 
  (fn [& parse-result]
    ;(cons key parse-result)))
    (into [key] parse-result)))

(defn enlive-non-terminal-reduction [key] 
  (fn [& parse-result]
    {:tag key, :content parse-result}))

(defn raw-non-terminal-reduction [& parse-result] 
  (if parse-result
    (make-flattenable parse-result)
    nil)) 

(defn hide-tag [parser]
  (red parser raw-non-terminal-reduction))

(def standard-non-terminal-reduction hiccup-non-terminal-reduction)

(defn apply-standard-reductions [grammar]
  (into {} (for [[k v] grammar]
             (if (:red v) [k v]
               [k (assoc v :red (standard-non-terminal-reduction k))]))))

;; End-user parsing function

(defn parse [grammar parser text]
  (clear!)
  (let [grammar (apply-standard-reductions grammar)
        tramp (make-tramp grammar text)
        parser (nt parser)]
    (push-full-listener tramp [0 parser] (TopListener tramp))    
    (if-let [all-parses (run tramp)]
      all-parses 
      @(:failure tramp))))

;; Variation, but not for end-user

;(defn negative-parse? 
;  "takes pre-processed grammar and parser" 
;  [grammar parser text]  
;  (let [tramp (make-tramp grammar text)]
;    (push-listener tramp [0 parser] (TopListener tramp))    
;    (empty? (run tramp))))
;    

(def grammar1 {:s (alt (string "a") (string "aa") (string "aaa"))})
(def grammar2 {:s (alt (string "a") (string "b"))})
(def grammar3 {:s (alt (cat (string "a") (nt :s)) Epsilon)})
(def grammar4 {:y (string "b")
               :x (cat (string "a") (nt :y))})            
(def grammar5 {:s (cat (string "a") (string "b") (string "c"))})
(def grammar6 {:s (alt (cat (string "a") (nt :s)) (string "a"))})
(def grammar7 {:s (alt (cat (string "a") (nt :s)) Epsilon)})
; This next one is fairly slow
(def grammar8 {:s (alt (cat (string "a") (nt :s) Epsilon) (string "a"))})
(def grammar9 {:s (alt (cat (string "a") (nt :s))
                       (cat (string "b") (nt :s))
                       Epsilon)})
(def grammar10 {:s (alt (cat (nt :s) (string "a") )
                       (cat (nt :s) (string "b") )
                       Epsilon)})
(def grammar11 {:s (alt (cat (nt :s) (string "a")) (string "a"))})
(def grammar12 {:s (alt (nt :a) (nt :a) (nt :a))
                :a (alt (cat (nt :s) (string "a")) (string "a"))})
(def grammar13 {:s (nt :a)
                :a (alt (cat (nt :s) (string "a")) (string "a"))})
(def amb-grammar {:s (alt (string "b") 
                          (cat (nt :s) (nt :s))
                          (cat (nt :s) (nt :s) (nt :s)))})
(def paren-grammar {:s (alt (cat (string "(") (string ")"))
                            (cat (string "(") (nt :s) (string ")"))
                            (cat (nt :s) (nt :s)))})
(def non-ll-grammar {:s (alt (nt :a) (nt :b))
                      :a (alt (cat (string "a") (nt :a) (string "b"))
                              Epsilon)
                      :b (alt (cat (string "a") (nt :b) (string "bb"))
                              Epsilon)})
(def grammar14 {:s (cat (opt (string "a")) (string "b"))})
(def grammar15 {:s (cat (opt (string "a")) (opt (string "b")))})
(def grammar16 {:s (plus (string "a"))})
(def grammar17 {:s (cat (plus (string "a")) (string "b"))})
(def grammar18 {:s (cat (plus (string "a")) (string "a"))})
(def grammar19 {:s (cat (string "a") (plus (alt (string "b")
                                                (string "c"))))})
(def grammar20 {:s (cat (string "a") (plus (cat (string "b")
                                                (string "c"))))})
(def grammar21 {:s (cat (string "a") (plus (alt (string "b")
                                                (string "c")))
                        (string "b"))})
(def grammar22 {:s (star (string "a"))})
(def grammar23 {:s (cat (star (string "a")) (string "b"))})
(def grammar24 {:s (cat (star (string "a")) (string "a"))})
(def grammar25 {:s (cat (string "a") (star (alt (string "b")
                                                (string "c"))))})
(def grammar26 {:s (cat (string "a") (star (cat (string "b")
                                                (string "c"))))})
(def grammar27 {:s (cat (string "a") (star (alt (string "b")
                                                (string "c")))
                        (string "b"))})
(def grammar28 {:s (regexp "a[0-9]b+c")})
(def grammar29 {:s (plus (opt (string "a")))})
(def paren-grammar 
  {:a (red (cat (string "(") (opt (nt :a)) (string ")"))
           (fn ([_ _] ())
             ([_ l _] (list l))))})
(def grammar30 {:s (alt (nt :a) (nt :b))
                :a (plus (cat (string "a") (string "b")))
                :b (plus (cat (string "a") (string "b")))})
;equal: [zero one | one zero]   ;; equal number of "0"s and "1"s.
;
;zero: "0" equal | equal "0"    ;; has an extra "0" in it.
;
;one: "1" equal | equal "1"     ;; has an extra "1" in it.
(def equal-zeros-ones {:equal (opt (alt (cat (nt :zero) (nt :one))
                                        (cat (nt :one) (nt :zero))))
                       :zero (alt (cat (string "0") (nt :equal))
                                  (cat (nt :equal) (string "0")))
                       :one (alt (cat (string "1") (nt :equal))
                                 (cat (nt :equal) (string "1")))})
(def grammar31 {:equal (alt (cat (string "0") (nt :equal) (string "1"))
                            (cat (string "1") (nt :equal) (string "0"))
                            (cat (nt :equal) (nt :equal))
                            Epsilon)})
; Another slow one
(def grammar32 {:s (alt (string "0")
                        (cat (nt :s) (nt :s))
                        Epsilon)})

(def grammar33 {:s (alt (cat (nt :s) (nt :s))
                        Epsilon)})
(def grammar34 {:s (alt (nt :s) Epsilon)})
(def grammar35 {:s (opt (cat (nt :s) (nt :s)))})
(def grammar36 {:s (cat (opt (nt :s)) (nt :s))})
(def grammar37 {:s (cat (nt :s) (opt (nt :s)))})
(def grammar38 {:s (regexp "a[0-9](bc)+")})
(def grammar39 {:s (cat (string "0") (hide (string "1"))(string "2"))})
(def grammar40 {:s (nt :aa)
                :aa (hide-tag (alt Epsilon (cat (string "a") (nt :aa))))})
(def grammar41 {:s (cat (string "b") (plus (string "a")))})
(def grammar42 {:s (cat (string "b") (star (string "a")))})
(def grammar43 {:s (cat (star (string "a")) (string "b"))})
(def grammar44 {:s (cat (look (string "ab")) (nt :ab))
                :ab (plus (alt (string "a") (string "b")))})
(def grammar45 {:s (cat (nt :ab) (look (string "ab")))
                :ab (plus (alt (string "a") (string "b")))})

(def grammar46 {:s (cat (nt :ab) (look Epsilon))
                :ab (plus (alt (string "a") (string "b")))})
(def grammar47 {:s (cat (neg (string "ab")) (nt :ab))
                :ab (plus (alt (string "a") (string "b")))})
(def grammar48 {:s (cat (nt :ab) (neg (string "ab")))
                :ab (plus (alt (string "a") (string "b")))})
(def grammar49 {:s (cat (nt :ab) (neg Epsilon))
                :ab (plus (alt (string "a") (string "b")))})
; Grammar for odd number of a's.  
(def grammar50 {:s (alt (cat (string "a") (nt :s) (string "a"))
                        (string "a"))})
(def grammar51 {:s (hide-tag (alt (cat (string "a") (nt :s) (string "a"))
                                  (string "a")))})
(def grammar52 {:s (hide-tag (alt (cat (string "a") (nt :s) (string "b"))
                                  (string "a")))})
(def grammar53 {:s (hide-tag (alt (cat (string "a") (nt :s) (string "a"))
                                  (string "b")))})
(def grammar54 {:s (cat (string "a")
                        (star (string "aa")))})
(def grammar55 {:s (alt (cat (string "a") (nt :s) (opt (string "a")))
                        (string "a"))})
(def grammar56 {:s (alt (string "a")
                        (cat (string "a") (nt :s) (string "a"))
                        )})
;; PEG grammars
(def grammar57 {:s (ord (plus (string "aa"))
                        (plus (string "a")))})

(def grammar58 {:s (cat (ord (plus (string "aa"))
                             (plus (string "a")))
                        (string "b"))})

(def grammar59 {:S (cat (look (cat (nt :A) (string "c")))
                        (plus (string "a"))
                        (nt :B)
                        (neg (ord (string "a") (string "b") (string "c"))))
                :A (cat (string "a") (opt (nt :A)) (string "b"))
                :B (hide-tag (cat (string "b") (opt (nt :B)) (string "c")))})
  
;Value   ← [0-9.]+ / '(' Expr ')'
;Product ← Expr (('*' / '/') Expr)*
;Sum     ← Expr (('+' / '-') Expr)*
;Expr    ← Product / Sum / Value

(def grammar60 {:Expr (ord (nt :Product) (nt :Sum) (nt :Value))
                :Product (cat (nt :Expr) 
                              (star (cat (alt (string "*")
                                              (string "/"))
                                         (nt :Expr))))
                :Sum (cat (nt :Expr)
                          (star (cat (alt (string "+")
                                          (string "-"))
                                     (nt :Expr))))
                :Value (alt (regexp "[0-9]+")
                            (cat (string "(")
                                 (nt :Expr)
                                 (string ")")))})
                            

(def grammar61 {:Expr (alt (nt :Product) (nt :Value))
                :Product (cat (nt :Expr) 
                              (star (cat (alt (string "*")
                                              (string "/"))
                                         (nt :Expr))))                
                :Value (alt (string "[0-9]+")
                            (cat (string "(")
                                 (nt :Expr)
                                 (string ")")))})

(def grammar62 {:Expr (alt (nt :Product) (string "0"))
                :Product (plus (nt :Expr))}) 
                
(def grammar63 {:Expr (alt (nt :Expr) (string "0"))})
(def grammar64 {:Expr (hide-tag (alt (nt :Product) 
                                     (cat (neg (nt :Product)) (nt :Sum))
                                     (cat (neg (nt :Product))
                                          (neg (nt :Sum))
                                          (nt :Value))))
                :Product (cat (nt :Expr) 
                              (star (cat (alt (string "*")
                                              (string "/"))
                                         (nt :Expr))))
                :Sum (cat (nt :Expr)
                          (star (cat (alt (string "+")
                                          (string "-"))
                                     (nt :Expr))))
                :Value (alt (regexp "[0-9]+")
                            (cat (string "(")
                                 (nt :Expr)
                                 (string ")")))})

(def grammar65 {:s (cat (alt (plus (string "aa"))
                             (cat 
                               (neg (plus (string "aa")))
                               (plus (string "a"))))
                        (string "b"))})

(def grammar66 {:s (neg (nt :s))})
(def grammar67 {:s (cat (neg (nt :s)) (string "0"))})
(def grammar68 {:s (cat (neg (nt :a)) (string "0"))
                :a (neg (nt :s))})