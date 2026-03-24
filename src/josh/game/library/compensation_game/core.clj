(ns josh.game.library.compensation-game.core
  "Compensation alignment game -- five-stage extensive form.

   Stage 1: Firm chooses a compensation menu
   Stage 2: Workers choose a package or reject
   Stage 3: Workers choose effort level (period 1)
   Stage 4: Workers decide stay or leave (retention)
   Stage 5: Workers choose effort level (period 2)

   Three mechanisms differentiate compensation structures:
   1. Output linkage: commission scales with effort, salary does not
   2. Retention: unvested equity is forfeited on leave (golden handcuffs)
   3. Effort-value feedback: period-1 output shifts equity price in period 2

   The equilibrium discovers which structures incentivize effort and
   retention for which worker types."
  (:require
   [josh.game.library.compensation-game.schema :as comp-schema]
   [malli.experimental :as mx]
   [mieza.sdk.array :refer [coll->ByteArray]]
   [mieza.sdk.entities.compensation.compute :as compute]
   [mieza.sdk.entities.compensation.package :as pkg]
   [mieza.sdk.entities.compensation.profiles :as profiles]
   [mieza.sdk.entities.compensation.types :as comp-types]
   [mieza.sdk.entities.compensation.valuation :as val-model]
   [mieza.sdk.entities.compensation.vesting :as vesting]
   [mieza.sdk.players :refer [->ChancePlayer]]
   [mieza.sdk.protocols :as ef]))

(def game-form-schema comp-schema/schema)

;; =============================================================================
;; Output-linked compensation (higher-order: one fn, two perspectives)
;; =============================================================================

(mx/defn ^:private adjusted-comp-per-period :- number?
  "Per-period compensation value adjusted for output level.

   valuation-fn computes a single component's PV from one perspective:
     - candidate: (fn [pkg vm profile horizon] ...) using candidate-utility
     - firm:      (fn [pkg vm profile horizon] ...) using company-cost

   For each component, divides the full-horizon PV by n-periods,
   then applies output linkage: fixed portion is constant, variable
   portion scales with output_multiplier."
  [package :- pkg/CompensationPackage
   valuation-model :- val-model/ValuationModel
   profile :- [:or profiles/CandidateProfile profiles/FirmProfile]
   total-horizon :- pos-int?
   n-periods :- pos-int?
   output-linkage :- [:map-of :keyword number?]
   output-multiplier :- number?
   valuation-fn :- fn?]
  (let [period-divisor (double n-periods)]
    (reduce
     (fn [total comp]
       (let [linkage  (get output-linkage (:component_type comp) 0.0)
             nominal  (/ (valuation-fn {:components [comp]} valuation-model
                           profile total-horizon)
                         period-divisor)
             fixed    (* (- 1.0 linkage) nominal)
             variable (* linkage nominal output-multiplier)]
         (+ total fixed variable)))
     0.0
     (:components package))))

;; =============================================================================
;; Vesting and equity forfeiture
;; =============================================================================

(mx/defn ^:private vested-equity-value :- number?
  "Value of equity components that have vested by the given month.
   Unvested equity is forfeited (worth 0 to the worker)."
  [package :- pkg/CompensationPackage
   valuation-model :- val-model/ValuationModel
   candidate-profile :- profiles/CandidateProfile
   months-elapsed :- nat-int?]
  (reduce
   (fn [total comp]
     (if (comp-types/equity-types (:component_type comp))
       (let [schedule (get-in comp [:temporal :schedule])
             frac     (if schedule
                        (vesting/vesting-fraction schedule months-elapsed)
                        1.0)
             nominal  (compute/candidate-utility {:components [comp]} valuation-model
                        candidate-profile 1)]
         (+ total (* frac nominal)))
       total))
   0.0
   (:components package)))

;; =============================================================================
;; Effort-value feedback
;; =============================================================================

(mx/defn ^:private adjust-valuation-for-effort :- val-model/ValuationModel
  "Shift current valuation based on period-1 output.
   Higher output -> higher equity price in period 2."
  [valuation-model :- val-model/ValuationModel
   effort-impact :- number?
   output-multiplier :- number?]
  (if (zero? effort-impact)
    valuation-model
    (let [shift (* effort-impact (- output-multiplier 1.0))
          new-val (* (:current_valuation valuation-model) (+ 1.0 shift))]
      (assoc valuation-model :current_valuation (max 0.01 new-val)))))

;; =============================================================================
;; Period payoff -- the composable building block
;; =============================================================================

(mx/defn ^:private period-payoff :- [:map [:worker-comp number?] [:worker-disutility number?]
                                          [:firm-output number?] [:firm-cost number?]]
  "Compute one period's payoff components for a single worker type.
   Returns a map of decomposed values -- the caller composes them."
  [package :- pkg/CompensationPackage
   valuation-model :- val-model/ValuationModel
   candidate-profile :- profiles/CandidateProfile
   firm-profile :- profiles/FirmProfile
   worker-type :- comp-schema/WorkerType
   effort-level :- comp-schema/EffortLevel
   total-horizon :- pos-int?
   period-months :- pos-int?
   output-linkage :- [:map-of :keyword number?]]
  (let [e-mult (:output_multiplier effort-level)]
    {:worker-comp       (adjusted-comp-per-period package valuation-model candidate-profile
                          total-horizon 2 output-linkage e-mult compute/candidate-utility)
     :worker-disutility (:disutility effort-level)
     :firm-output       (* (:base_output worker-type) e-mult (double period-months))
     :firm-cost         (adjusted-comp-per-period package valuation-model firm-profile
                          total-horizon 2 output-linkage e-mult compute/company-cost)}))

(defn- net-worker [period-result]
  (- (:worker-comp period-result) (:worker-disutility period-result)))

(defn- net-firm [period-result]
  (- (:firm-output period-result) (:firm-cost period-result)))

;; =============================================================================
;; Full game payoff -- composes period-payoff with retention logic
;; =============================================================================

(mx/defn ^:private compute-payoffs :- some?
  "Compute payoffs for the full 2-period game."
  [config :- comp-schema/schema
   chosen-menu :- comp-schema/CompensationMenu
   package-actions :- [:vector nat-int?]
   effort1-actions :- [:vector nat-int?]
   stay-actions :- [:vector nat-int?]
   effort2-actions :- [:vector nat-int?]]
  (let [{:keys [firm_profile valuation_model period_months vesting_midpoint_months
                worker_types output_linkage effort_impact unfilled_role_cost
                payoff_scale]} config
        scale         (double payoff_scale)
        n-pkg         (count (:packages chosen-menu))
        total-horizon (* 2 period_months)

        per-type
        (fn [wt pkg-action e1-action stay-action e2-action]
          (if (= pkg-action n-pkg)
            {:firm-contrib (* (:share wt) unfilled_role_cost)
             :worker-pay   (* 2.0 (:reservation_utility wt))}

            (let [package (nth (:packages chosen-menu) pkg-action)
                  cp      (:candidate_profile wt)
                  effort1 (nth (:effort_levels wt) e1-action)
                  e1-mult (:output_multiplier effort1)
                  val2    (adjust-valuation-for-effort valuation_model effort_impact e1-mult)

                  p1 (period-payoff package valuation_model cp firm_profile wt effort1
                       total-horizon period_months output_linkage)]

              (if (zero? stay-action)
                ;; STAYED
                (let [effort2 (nth (:effort_levels wt) e2-action)
                      p2 (period-payoff package val2 cp firm_profile wt effort2
                           total-horizon period_months output_linkage)]
                  {:firm-contrib (* (:share wt) (+ (net-firm p1) (net-firm p2)))
                   :worker-pay  (+ (net-worker p1) (net-worker p2))})

                ;; LEFT
                (let [vested (vested-equity-value package val2 cp vesting_midpoint_months)]
                  {:firm-contrib (* (:share wt) (+ (net-firm p1) unfilled_role_cost))
                   :worker-pay  (+ (net-worker p1) vested (:reservation_utility wt))})))))

        results     (mapv per-type worker_types package-actions
                      effort1-actions stay-actions effort2-actions)
        firm-total  (reduce + 0.0 (map :firm-contrib results))
        worker-pays (mapv :worker-pay results)]
    (int-array (into [(int (* scale firm-total))]
                     (map (fn [v] (int (* scale v))) worker-pays)))))

;; =============================================================================
;; Game tree -- stage dispatch
;; =============================================================================

(defn- stage-and-wt-idx
  "Returns [stage worker-type-index] for a given turn number and n-types."
  [turn n-types]
  (cond
    (zero? turn)              [:menu nil]
    (<= turn n-types)         [:package (dec turn)]
    (<= turn (* 2 n-types))   [:effort-1 (- turn n-types 1)]
    (<= turn (* 3 n-types))   [:stay-leave (- turn (* 2 n-types) 1)]
    :else                     [:effort-2 (- turn (* 3 n-types) 1)]))

(defn- effort-level-labels
  "Action labels for effort level choices."
  [worker-types wt-idx]
  (mapv (fn [el] (name (:id el)))
        (:effort_levels (nth worker-types wt-idx))))

(defn- effort-level-count
  "Number of effort level actions for a worker type."
  [worker-types wt-idx]
  (count (:effort_levels (nth worker-types wt-idx))))

(defn- worker-observation
  "Build the observation vector for a worker at a given stage.
   Each stage reveals progressively more of the worker's own history."
  [acts n-types wt-idx stage]
  (let [menu-choice (first acts)]
    (case stage
      :package    [wt-idx menu-choice]
      :effort-1   [wt-idx menu-choice (nth acts (inc wt-idx))]
      :stay-leave [wt-idx menu-choice (nth acts (inc wt-idx))
                    (nth acts (+ 1 n-types wt-idx))]
      :effort-2   [wt-idx menu-choice (nth acts (inc wt-idx))
                    (nth acts (+ 1 n-types wt-idx))
                    (nth acts (+ 1 (* 2 n-types) wt-idx))])))

;; =============================================================================
;; Game record
;; =============================================================================

(defrecord CompensationGame
  [^clojure.lang.PersistentVector players
   ^clojure.lang.PersistentVector acts
   ^clojure.lang.PersistentVector menus
   ^clojure.lang.PersistentVector worker-types
   n-types
   chosen-menu
   package-count
   config]

  ef/ExtensiveFormGame

  (players [_] players)

  (actions [_]
    (let [[stage wt-idx] (stage-and-wt-idx (count acts) n-types)]
      (case stage
        :menu       (vec (range (count menus)))
        :package    (vec (range (inc package-count)))
        :effort-1   (vec (range (effort-level-count worker-types wt-idx)))
        :stay-leave [0 1]
        :effort-2   (vec (range (effort-level-count worker-types wt-idx))))))

  (actions-labels [_]
    (let [[stage wt-idx] (stage-and-wt-idx (count acts) n-types)]
      (case stage
        :menu       (mapv :name menus)
        :package    (conj (mapv (fn [p] (or (:role p) "Package"))
                                (:packages chosen-menu))
                          "Reject")
        :effort-1   (effort-level-labels worker-types wt-idx)
        :stay-leave ["Stay" "Leave"]
        :effort-2   (effort-level-labels worker-types wt-idx))))

  (history [_] acts)

  (act [this action]
    (if (zero? (count acts))
      (let [menu (nth menus action)]
        (assoc this
               :acts (conj acts action)
               :chosen-menu menu
               :package-count (count (:packages menu))))
      (assoc this :acts (conj acts action))))

  (terminal? [_]
    (= (count acts) (+ 1 (* 4 n-types))))

  (terminal? [this _]
    (ef/terminal? this))

  (observe [_ _]
    (let [[stage wt-idx] (stage-and-wt-idx (count acts) n-types)]
      (coll->ByteArray
       (if (= stage :menu)
         [0]
         (worker-observation acts n-types wt-idx stage)))))

  (turn [_]
    (let [turn (count acts)]
      (when (< turn (+ 1 (* 4 n-types)))
        (let [[stage wt-idx] (stage-and-wt-idx turn n-types)]
          (if (= stage :menu)
            (nth players 0)
            (nth players (inc wt-idx)))))))

  (outcomes [this]
    (if (ef/terminal? this)
      (let [pkg-acts (subvec acts 1 (+ 1 n-types))
            e1-acts  (subvec acts (+ 1 n-types) (+ 1 (* 2 n-types)))
            sl-acts  (subvec acts (+ 1 (* 2 n-types)) (+ 1 (* 3 n-types)))
            e2-acts  (subvec acts (+ 1 (* 3 n-types)))]
        (compute-payoffs config chosen-menu pkg-acts e1-acts sl-acts e2-acts))
      (int-array (repeat (count players) 0))))

  (outcome [this player]
    (aget ^ints (ef/outcomes this)
          (.indexOf ^clojure.lang.PersistentVector players player))))

;; =============================================================================
;; Factory
;; =============================================================================

(mx/defn ^:private make-players :- [:vector some?]
  "Construct player vector from game config."
  [config :- comp-schema/schema]
  (into [(->ChancePlayer false {:name "Firm"})]
        (mapv (fn [wt] (->ChancePlayer false {:name (:name wt)}))
              (:worker_types config))))

(defn new-game
  "Create a new compensation alignment game from a validated config."
  ([config] (new-game config nil))
  ([config players]
   (let [ps (or players (make-players config))
         wt (:worker_types config)]
     (map->CompensationGame
      {:players       (vec ps)
       :acts          []
       :menus         (:menus config)
       :worker-types  wt
       :n-types       (count wt)
       :chosen-menu   nil
       :package-count nil
       :config        config}))))
