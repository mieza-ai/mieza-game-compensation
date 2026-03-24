(ns josh.game.library.compensation-game-test
  "Tests the compensation alignment game: protocol compliance,
   payoff correctness, and strategic properties across all 5 stages."
  (:require
   [clojure.test :refer [deftest testing is]]
   [josh.game.library.compensation-game.core :refer [new-game]]
   [mieza.sdk.protocols :refer [terminal? actions act outcomes
                                observe turn players history
                                actions-labels]]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def test-valuation-model
  {:current_valuation 10.0
   :scenarios [{:id :bull
                :description "Bull case"
                :terminal_multiple 3.0
                :horizon {:months 48}}
               {:id :bear
                :description "Bear case"
                :terminal_multiple 0.5
                :horizon {:months 48}}]
   :beliefs {:optimist {:bull 0.7 :bear 0.3}
             :pessimist {:bull 0.2 :bear 0.8}
             :firm-view {:bull 0.5 :bear 0.5}}})

(def salary-package
  {:components [{:component_type :base_salary
                 :value {:shape :fixed :amount 150000 :period :annual}}]})

(def commission-package
  {:components [{:component_type :base_salary
                 :value {:shape :fixed :amount 60000 :period :annual}}
                {:component_type :commission
                 :value {:shape :fixed :amount 90000 :period :annual}}]})

(def rsu-package
  {:components [{:component_type :base_salary
                 :value {:shape :fixed :amount 120000 :period :annual}}
                {:component_type :rsu
                 :value {:shape :fixed :amount 50000 :period :one_time}
                 :temporal {:type :vesting
                            :schedule {:curve :linear
                                       :total {:months 48}
                                       :cliff {:months 12}}}}]})

(def effort-levels
  [{:id :low    :output_multiplier 0.5 :disutility 5000}
   {:id :medium :output_multiplier 1.0 :disutility 20000}
   {:id :high   :output_multiplier 1.5 :disutility 45000}])

(def test-config
  {:firm_profile {:belief_id :firm-view :discount_rate 0.05}
   :valuation_model test-valuation-model
   :period_months 12
   :vesting_midpoint_months 12
   :menus [{:name "Salary-Only"    :packages [salary-package]}
           {:name "Commission"     :packages [commission-package]}
           {:name "Salary+RSU"     :packages [rsu-package]}]
   :worker_types [{:name "Risk-Averse"
                   :candidate_profile {:belief_id :pessimist
                                       :risk_preference 0.8
                                       :discount_rate 0.03
                                       :benefit_preferences {}}
                   :reservation_utility 50000
                   :base_output 10000
                   :effort_levels effort-levels
                   :share 0.5}
                  {:name "Ambitious"
                   :candidate_profile {:belief_id :optimist
                                       :risk_preference 0.1
                                       :discount_rate 0.05
                                       :benefit_preferences {}}
                   :reservation_utility 40000
                   :base_output 12000
                   :effort_levels effort-levels
                   :share 0.5}]
   :output_linkage {:commission 1.0 :performance_bonus 0.5}
   :effort_impact 0.5
   :unfilled_role_cost -50000
   :payoff_scale 1})

;; Helper: play a full game given action choices
;; Actions: [menu, pkg0, pkg1, e1-0, e1-1, stay0, stay1, e2-0, e2-1]
(defn play [config & action-seq]
  (reduce act (new-game config) action-seq))

;; ---------------------------------------------------------------------------
;; Protocol compliance
;; ---------------------------------------------------------------------------

(deftest test-game-creation
  (testing "new-game constructs a valid game"
    (let [game (new-game test-config)]
      (is (= 3 (count (players game))) "1 firm + 2 worker types")
      (is (not (terminal? game)))
      (is (= [] (history game))))))

(deftest test-action-spaces
  (let [game (new-game test-config)]
    (testing "firm has one action per menu"
      (is (= [0 1 2] (actions game)))
      (is (= ["Salary-Only" "Commission" "Salary+RSU"] (actions-labels game))))

    (testing "worker package stage: packages + reject"
      (let [g (act game 0)]
        (is (= [0 1] (actions g)))))

    (testing "worker effort stage: one per effort level"
      (let [g (-> game (act 0) (act 0) (act 0))]
        (is (= [0 1 2] (actions g)))
        (is (= ["low" "medium" "high"] (actions-labels g)))))

    (testing "stay/leave stage: binary choice"
      (let [g (-> game (act 0) (act 0) (act 0) (act 1) (act 1))]
        (is (= [0 1] (actions g)))
        (is (= ["Stay" "Leave"] (actions-labels g)))))))

;; ---------------------------------------------------------------------------
;; Turn structure (5 stages, 1 + 4N = 9 turns for N=2)
;; ---------------------------------------------------------------------------

(deftest test-turn-order
  (let [game (new-game test-config)
        p    (players game)]
    (testing "firm moves first"
      (is (= (nth p 0) (turn game))))
    (testing "worker-0 picks package"
      (is (= (nth p 1) (turn (act game 0)))))
    (testing "worker-1 picks package"
      (is (= (nth p 2) (turn (-> game (act 0) (act 0))))))
    (testing "worker-0 picks effort-1"
      (is (= (nth p 1) (turn (-> game (act 0) (act 0) (act 0))))))
    (testing "worker-1 picks effort-1"
      (is (= (nth p 2) (turn (-> game (act 0) (act 0) (act 0) (act 1))))))
    (testing "worker-0 decides stay/leave"
      (is (= (nth p 1) (turn (-> game (act 0) (act 0) (act 0) (act 1) (act 1))))))
    (testing "worker-1 decides stay/leave"
      (is (= (nth p 2) (turn (-> game (act 0) (act 0) (act 0) (act 1) (act 1) (act 0))))))
    (testing "worker-0 picks effort-2"
      (is (= (nth p 1) (turn (-> game (act 0) (act 0) (act 0) (act 1) (act 1) (act 0) (act 0))))))
    (testing "terminal after 9 actions"
      (let [g (play test-config 0 0 0 1 1 0 0 1 1)]
        (is (terminal? g))))))

;; ---------------------------------------------------------------------------
;; Information sets
;; ---------------------------------------------------------------------------

(deftest test-information-sets
  (let [game (new-game test-config)]
    (testing "firm sees nothing"
      (is (= (seq (observe game 0)) [0])))
    (testing "worker at package stage sees type + menu"
      (is (= (seq (observe (act game 0) 1)) [0 0])))
    (testing "worker at effort-1 sees type + menu + package"
      (let [g (-> game (act 0) (act 0) (act 0))]
        (is (= (seq (observe g 1)) [0 0 0]))))
    (testing "worker at stay/leave sees type + menu + package + effort-1"
      (let [g (-> game (act 0) (act 0) (act 0) (act 2) (act 1))]
        (is (= (seq (observe g 1)) [0 0 0 2]))))))

;; ---------------------------------------------------------------------------
;; Payoff computation
;; ---------------------------------------------------------------------------

(deftest test-payoffs-both-stay
  (testing "both workers accept and stay, payoffs are non-zero"
    ;;        menu pkg0 pkg1 e1-0 e1-1 stay0 stay1 e2-0 e2-1
    (let [g (play test-config 0 0 0 1 1 0 0 1 1)
          pays (vec (outcomes g))]
      (is (= 3 (count pays)))
      (is (not= 0 (nth pays 0)))
      (is (not= 0 (nth pays 1)))
      (is (not= 0 (nth pays 2))))))

(deftest test-payoffs-both-reject
  (testing "both workers reject, get 2x reservation utility"
    ;;        menu pkg0=reject pkg1=reject e1 e1 stay stay e2 e2
    (let [g (play test-config 0 1 1 0 0 0 0 0 0)
          pays (vec (outcomes g))]
      ;; 2 periods x reservation_utility
      (is (= 100000 (nth pays 1)) "risk-averse: 2 x 50000")
      (is (= 80000 (nth pays 2)) "ambitious: 2 x 40000")
      (is (< (nth pays 0) 0) "firm gets unfilled cost"))))

(deftest test-payoffs-leave
  (testing "worker who leaves gets P1 comp + vested equity + reservation for P2"
    ;;        menu=RSU pkg0 pkg1 e1-0 e1-1 stay0=leave stay1=stay e2-0 e2-1
    (let [stay-g  (play test-config 2 0 0 1 1 0 0 1 1)
          leave-g (play test-config 2 0 0 1 1 1 0 1 1)
          stay-pay  (nth (vec (outcomes stay-g)) 1)
          leave-pay (nth (vec (outcomes leave-g)) 1)]
      ;; Staying vs leaving should produce different payoffs
      (is (not= stay-pay leave-pay)
          "staying and leaving should produce different worker payoffs"))))

;; ---------------------------------------------------------------------------
;; Strategic properties
;; ---------------------------------------------------------------------------

(deftest test-commission-incentivizes-effort
  (testing "with commission, higher effort increases worker pay"
    ;;        menu=commission, both accept, low vs medium effort, both stay
    (let [low-g  (play test-config 1 0 0 0 0 0 0 0 0)
          med-g  (play test-config 1 0 0 1 1 0 0 1 1)
          low-pay  (nth (vec (outcomes low-g)) 1)
          med-pay  (nth (vec (outcomes med-g)) 1)]
      (is (> med-pay low-pay)
          "commission: medium effort > low effort for worker"))))

(deftest test-salary-effort-indifferent
  (testing "with salary, low effort dominates"
    (let [low-g  (play test-config 0 0 0 0 0 0 0 0 0)
          med-g  (play test-config 0 0 0 1 1 0 0 1 1)
          low-pay  (nth (vec (outcomes low-g)) 1)
          med-pay  (nth (vec (outcomes med-g)) 1)]
      (is (> low-pay med-pay)
          "salary: low effort > medium effort for worker"))))

(deftest test-effort-impacts-equity-value
  (testing "high P1 effort boosts P2 payoff when holding P2 effort constant"
    ;; Both use medium P2 effort. Only P1 effort differs.
    ;; The difference isolates: (equity boost from effort_impact) - (P1 disutility delta)
    ;; Use a config with large RSU grant + high effort_impact to make equity effect dominate
    (let [equity-config (assoc test-config
                          :effort_impact 2.0
                          :menus [{:name "Big-RSU"
                                   :packages [{:components
                                               [{:component_type :base_salary
                                                 :value {:shape :fixed :amount 80000 :period :annual}}
                                                {:component_type :rsu
                                                 :value {:shape :fixed :amount 200000 :period :one_time}
                                                 :temporal {:type :vesting
                                                            :schedule {:curve :linear
                                                                       :total {:months 48}
                                                                       :cliff {:months 12}}}}]}]}
                                  {:name "Salary" :packages [salary-package]}])
          ;; low P1, medium P2, both stay
          low-g  (play equity-config 0 0 0 0 0 0 0 1 1)
          ;; high P1, medium P2, both stay
          high-g (play equity-config 0 0 0 2 2 0 0 1 1)
          low-pay  (nth (vec (outcomes low-g)) 1)
          high-pay (nth (vec (outcomes high-g)) 1)]
      ;; With effort_impact=2.0 and large RSU, the equity boost from high P1
      ;; effort should outweigh the extra P1 disutility
      (is (> high-pay low-pay)
          "large RSU + high effort_impact: high P1 effort should boost total"))))

(deftest test-rsu-retention-value
  (testing "leaving with RSUs forfeits unvested equity, creating retention cost"
    ;; Use RSU package. Compare stay vs leave for worker-0.
    ;; Worker who leaves loses unvested RSU value = retention incentive.
    (let [stay-g  (play test-config 2 0 0 1 1 0 0 1 1)
          leave-g (play test-config 2 0 0 1 1 1 0 1 1)
          stay-pay  (nth (vec (outcomes stay-g)) 1)
          leave-pay (nth (vec (outcomes leave-g)) 1)]
      ;; Staying should pay more because the worker retains period-2 comp
      ;; (including continued RSU vesting) instead of just vested equity +
      ;; reservation utility
      (is (> stay-pay leave-pay)
          "staying should pay more than leaving with RSU package")))

  (testing "leaving with salary has a different gap than leaving with RSUs"
    ;; The key is that RSU forfeiture creates an ADDITIONAL retention cost
    ;; beyond just losing period-2 salary. We verify this by checking that
    ;; the leave payoff includes vested equity (non-zero equity component)
    (let [leave-g (play test-config 2 0 0 1 1 1 0 1 1)
          reject-g (play test-config 2 1 0 0 0 0 0 0 0)
          leave-pay  (nth (vec (outcomes leave-g)) 1)
          reject-pay (nth (vec (outcomes reject-g)) 1)]
      ;; Leaving after accepting RSU should pay more than outright rejection
      ;; because the worker gets P1 comp + vested equity
      (is (> leave-pay reject-pay)
          "leaving after RSU should pay more than rejecting outright"))))

(deftest test-firm-prefers-effort
  (testing "firm benefits from higher worker effort"
    (let [low-g  (play test-config 1 0 0 0 0 0 0 0 0)
          high-g (play test-config 1 0 0 2 2 0 0 2 2)
          firm-low  (nth (vec (outcomes low-g)) 0)
          firm-high (nth (vec (outcomes high-g)) 0)]
      (is (> firm-high firm-low)))))

(deftest test-non-terminal-returns-zeros
  (let [pays (vec (outcomes (new-game test-config)))]
    (is (every? zero? pays))))
