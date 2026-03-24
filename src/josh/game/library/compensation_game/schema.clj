(ns josh.game.library.compensation-game.schema
  "Gameform schema for the compensation alignment game.

   Composes SDK compensation entities with game-specific concepts
   (effort levels, worker types, compensation menus, retention dynamics)
   into the full Malli schema that validates game configurations."
  (:require
   [mieza.sdk.entities.common :as common]
   [mieza.sdk.entities.compensation.package :as pkg]
   [mieza.sdk.entities.compensation.profiles :as profiles]
   [mieza.sdk.entities.compensation.valuation :as val-model]))

;; ---------------------------------------------------------------------------
;; Effort levels -- the worker's strategic choice
;; ---------------------------------------------------------------------------

(def EffortLevel
  "A discrete effort choice available to the worker.

   output_multiplier scales the worker's base output. Higher multipliers
   produce more value for the firm but cost the worker more disutility.
   The equilibrium discovers which comp structures make high effort
   incentive-compatible."
  [:map
   [:id :keyword]
   [:output_multiplier common/PositiveNumber]
   [:disutility common/NonNegativeNumber]])

;; ---------------------------------------------------------------------------
;; Worker type -- private information in the Bayesian game
;; ---------------------------------------------------------------------------

(def WorkerType
  "A worker's private type. The firm's uncertainty over this type space
   is the hidden information that makes this a Bayesian screening game.

   Each type wraps a CandidateProfile with game-specific fields:
   - reservation_utility: outside option value per period
   - base_output: output at effort multiplier 1.0
   - effort_levels: discrete effort choices
   - share: firm's prior probability of this type"
  [:map
   [:name common/NonEmptyString]
   [:candidate_profile profiles/CandidateProfile]
   [:reservation_utility number?]
   [:base_output common/PositiveNumber]
   [:effort_levels [:vector {:min 2 :max 5} EffortLevel]]
   [:share common/Probability]])

(def WorkerTypeDistributionValidator
  "Validates that worker type shares sum to 1.0."
  [:fn
   {:error/message "worker type shares must sum to 1.0 +/- 0.001"}
   (fn [{:keys [worker_types]}]
     (< (Math/abs (- 1.0 (reduce + (map :share worker_types))))
        0.001))])

;; ---------------------------------------------------------------------------
;; Compensation menu -- the firm's action set
;; ---------------------------------------------------------------------------

(def CompensationMenu
  "A set of packages offered simultaneously. The worker self-selects."
  [:map
   [:name common/NonEmptyString]
   [:packages [:vector {:min 1 :max 5} pkg/schema]]])

;; ---------------------------------------------------------------------------
;; Game form schema
;; ---------------------------------------------------------------------------

(def schema
  "Malli gameform schema for the compensation alignment game.

   Five-stage game with two work periods:
     1. Firm chooses menu
     2. Workers choose packages (or reject)
     3. Workers choose effort (period 1)
     4. Workers decide stay or leave (retention decision)
     5. Workers choose effort (period 2, if stayed)

   Key parameters:
   - output_linkage: component_type -> fraction of value that scales with output
   - effort_impact: how much period-1 output shifts equity value in period 2
   - vesting_midpoint_months: months of vesting completed at the stay/leave decision
   - period_months: length of each work period"
  [:and
   [:map
    [:firm_profile profiles/FirmProfile]
    [:valuation_model val-model/ValuationModel]
    [:period_months pos-int?]
    [:vesting_midpoint_months pos-int?]
    [:menus [:vector {:min 2 :max 5} CompensationMenu]]
    [:worker_types [:vector {:min 1 :max 4} WorkerType]]
    [:output_linkage [:map-of :keyword common/Probability]]
    [:effort_impact {:default 0.0} common/NonNegativeNumber]
    [:unfilled_role_cost {:default 0} number?]
    [:payoff_scale {:default 100} pos-int?]]
   WorkerTypeDistributionValidator])

(def game-declaration
  "Canonical declaration for the compensation game.

   Includes shelf metadata (`:id`, `:name`, `:player-counts`, etc.) and the
   Malli `:schema` so `mieza.game.db` and other consumers stay DRY."
  {:id :compensation-game
   :name "Compensation Negotiation"
   :perfect-information? false
   :player-counts [2 3 4 5]
   :entities [:compensation-package :valuation-model :candidate-profile :firm-profile]
   :schema schema})
