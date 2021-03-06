(def *example-model*
     {:start ["the"]
      "the" ["cat" "mat"]
      "cat" ["ate" "miaowed"]
      "ate" ["fish" "cheezburger"]})

(defn rand-select [vec]
  (get vec (rand-int (count vec))))

(defn get-next
  "Gets a random token that could follow last-token"
  [model last-token]
  (rand-select (model last-token)))

(defn average [seq]
  (/ 
   (apply + seq)
   (count seq)))

(defn jumpiness [model]
  (let [raw (average (map #(count (distinct (model %))) (keys model)))]
    (/ (- raw 1) raw)))

(defn new-model [order]
  #^{:order order} {(vec (replicate order :start)) []})

(defn order [model] (:order (meta model)))

(defn generate-string   
  "Generates a random string from model"
  [model min max]
  (let [prev-tokens (replicate (order model) :start)]
    (loop [generated [] prev-tokens prev-tokens]
      (let [next (get-next model prev-tokens)]
	(cond
	  (and (= :end next) (< (.length generated) min)) "too small!"
	  (> (.length generated) max) generated ; (recur [] (replicate (order model) :start)) 
	  (= :end next) generated
	  :else (recur (conj generated next) (conj (vec (rest prev-tokens)) next)))))))

(defn add-tuple 
  "Eg, if tuple is [1 2], adds [1] -> 2 to the model. If tuple is [1 2 3], adds [1 2] -> 3 to the model."
  [model tuple]
  (if (not (= 
	    (count tuple) 
	    (+ 1 (:order (meta model)))))
	   (println "Model and tuple mismatched"))

  (let [left-side (vec (butlast tuple))
	right-side (last tuple)
	old-token-list (model left-side)]
    (if (nil? old-token-list)
      (assoc model left-side [right-side])
      (assoc model left-side (conj old-token-list right-side)))))
    
(defn add-token-string 
  "Read a string of tokens and add the information gained to model"
  [model tokens]
  (let [order (order model)]
    (loop [inputs (partition (+ 1 order)
			     1 
			     (into (vec (replicate order :start)) (conj tokens :end)))
	 model model]
    (if (empty? inputs) 
      model
      (recur (rest inputs) (add-tuple model (first inputs)))))))

(defn add-token-strings [model strings]
  (if (empty? strings) 
    model
    (add-token-strings (add-token-string model (first strings)) (rest strings))))


;;;

(import '(javax.sound.midi.spi MidiFileReader)
	'(javax.sound.midi MidiSystem Sequence MidiEvent MidiMessage ShortMessage Synthesizer Track)
	'(java.io File))

(defn map-indexed
  "Returns a lazy sequence consisting of the result of applying f to 0
  and the first item of coll, followed by applying f to 1 and the second
  item in coll, etc, until coll is exhausted. Thus function f should
  accept 2 arguments, index and item."
  {:added "1.2"}
  [f coll]
  (letfn [(mapi [idx coll]
            (lazy-seq
             (when-let [s (seq coll)]
               (if (chunked-seq? s)
                 (let [c (chunk-first s)
                       size (int (count c))
                       b (chunk-buffer size)]
                   (dotimes [i size]
                     (chunk-append b (f (+ idx i) (.nth c i))))
                   (chunk-cons (chunk b) (mapi (+ idx size) (chunk-rest s))))
                 (cons (f idx (first s)) (mapi (inc idx) (rest s)))))))]
    (mapi 0 coll)))

(defn get-sequence [file] (. MidiSystem getSequence file))

(defn get-sequencer [] (. MidiSystem getSequencer))

(defn open-and-play [file-name]
  (let [s (get-sequence (File. file-name))
	sr (get-sequencer)]
    (. sr open)
    (. sr setSequence s)
    (. sr start)
    sr))



(def note-on 0x90)
(def note-off 0x80)
(def note-names ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"])

(defn get-events [track]
  (for [i (range (. track size))] (. track get i)))

(defn interpret-event [event]
  ; (print "@" (. event getTick) " ")
  (let [message (.getMessage event)]
    (if (instance? ShortMessage message)
      (do
	; (print "Channel: " (.getChannel message) " ")
	(let [command (.getCommand message)]
	  (if (or (= command note-on) (= command note-off))
	    (let [key (.getData1 message)
		  octave (- (quot key 12) 1)
		  note (mod key 12)
		  note-name (nth note-names note)
		  velocity (.getData2 message)
		  on-or-off (if (= command note-off) "off" "on")]
	      ; (print "Note" on-or-off note-name octave "key=" key "velocity:" velocity "command:" command)
	      [on-or-off key velocity (. event getTick)]))))
    (print "Other message:" (. message getClass)))))


(defn interpret-track [trackno track]
  (println "Track" trackno ": size =" (. track size) "\n")
  (dorun (map #(interpret-event %) (get-events track))))


(defn interpret-seq [sequence]
     (dorun (map-indexed #(interpret-track %1 %2) (. sequence getTracks))))

(defstruct note :key :time :velocity)

(defn find-first [f seq]
  (loop [unchecked seq]
    (if (empty? unchecked) 
      nil
      (if (f (first unchecked))
	(first unchecked)
	(recur (rest unchecked))))))

(defn get-notes [track]
  (let [events (filter #(not (nil? %)) (map #(interpret-event %) (get-events track)))]
    (loop [notes [] unprocessed-events events]
      (if (empty? unprocessed-events) 
	notes
	(let [[on-off key velocity start-tick] (first unprocessed-events)]
	  (if (= "off" on-off)
	    (recur notes (rest unprocessed-events))

	    (let [[_ _ _ end-tick] (find-first #(= (take 2 %) ["off" key]) unprocessed-events)
		  note (struct note key (- end-tick start-tick) velocity)]
	      (recur (conj notes note) (rest unprocessed-events)))))))))
	

(defn get-notes-from-file [file trackno]
  (let [seq (get-sequence (File. file))]
    [seq (get-notes (nth (. seq getTracks) trackno))]))
   
(defn make-midi-event [on-off key velocity tick]
  (MidiEvent. (doto (ShortMessage.) (.setMessage (if (= on-off "on") -112 -128) key 127)) tick))

(defn notes-to-midi-events [notes]
  (loop [events [] unprocessed notes tick 0]
    (if (empty? unprocessed) events
	(let [next (first unprocessed)
	      new-tick (+ tick (next :time))
	      note-on (make-midi-event "on" (next :key) (next :velocity) tick)
	      note-off (make-midi-event "off" (next :key) (next :velocity) new-tick)]
	  (recur (conj (conj events note-on) note-off) (rest unprocessed) new-tick)))))
				       

(defn make-track [notes seq]
  (let [new-track (.createTrack seq)]
    (doall (map #(.add new-track %) (notes-to-midi-events notes)))))

;;;

(def *sr* (get-sequencer))

(defn write [seq trackno file-name]
  (let [wseq seq
	tracks (.getTracks seq)
	track (nth tracks trackno)]
    (doall (map #(if (not (= track %)) (. wseq deleteTrack %)) tracks))
    (. MidiSystem write wseq 0 (File. file-name))))

(defn go! []
  (if (.isOpen *sr*) (. *sr* stop))
  (let [[seq notes] (get-notes-from-file "fanfare.mid" 0)]
    (def *seq* seq)
    (def *notes* notes)
    (def num-tracks (count (.getTracks *seq*)))
    (println "\nSolo:" num-tracks)
    (def *model* (add-token-string (new-model 3) notes))
    (println "Jumpiness:" (double (jumpiness *model*)))
    (def *generated* (generate-string *model* 100 100000))
    (make-track *generated* *seq*)
    (def *sr* (get-sequencer))
    (. *sr* open)
    (. *sr* setSequence *seq*)
    (. *sr* setTrackSolo num-tracks true)
    (. *sr* start)))
