# Compensation Alignment Game

## What it solves

Given a set of possible compensation packages (salary, equity, bonuses, benefits) and a population of worker types with hidden preferences, **what compensation structure properly incentivizes a worker to exert effort on behalf of the firm?**

The equilibrium strategy tells the firm which menu of packages to offer so that:
- Workers self-select into packages that reveal their type
- The compensation structure induces optimal effort from each type
- The firm maximizes expected output minus compensation cost

## Game structure

```
Turn 0:  Firm chooses a compensation menu (set of packages)
Turn 1+: Each worker type observes the menu, chooses a package or rejects
```

**Players:** 1 firm + N worker types (2-5 total).

**Hidden information:**
- The firm does not know which worker type it faces
- Each worker type has private: risk preference, outside options, effort cost
- The firm and worker hold different beliefs about equity trajectory

## How effort works

Effort is endogenous — determined by the accepted compensation structure:

```
performance_comp = monthly value of performance bonuses + commissions
equity_comp      = expected monthly equity value under worker's beliefs
effort           = min(base + perf_sensitivity * perf_comp + equity_sensitivity * eq_comp, cap)
```

- **Firm payoff:** `effort * output_per_effort - company_cost(package)`
- **Worker payoff:** `candidate_utility(package) - effort * effort_cost_per_unit`

This creates the core tension: performance-heavy packages induce more effort (good for the firm) but cost the worker more disutility. The equilibrium balances these.

## Configuration

The game form is parameterized by:

| Field | Type | Description |
|-------|------|-------------|
| `firm_profile` | FirmProfile | Firm's beliefs and discount rate |
| `valuation_model` | ValuationModel | Equity scenarios + per-player beliefs |
| `horizon_months` | pos-int | Evaluation horizon |
| `menus` | [CompensationMenu] | Firm's action set (2-5 menus) |
| `worker_types` | [WorkerType] | Worker population (1-4 types) |
| `unfilled_role_cost` | number | Firm's cost if worker rejects |
| `payoff_scale` | pos-int | Dollar-to-integer multiplier |

Each `WorkerType` wraps a `CandidateProfile` (from the compensation SDK) with game-specific fields: `reservation_utility`, `effort_model`, and `share` (prior probability).

## Subgame composition

Designed as a composable subgame. The config map is the composition interface:
- **Upstream (e.g., interview game):** Updates worker type priors (`share`), salary bands, reservation utilities based on interview outcome
- **Downstream (e.g., work/retention game):** Consumes the accepted package to parameterize effort/retention decisions

## SDK dependencies

Reuses from `mieza.sdk.compensation`:
- `candidate-utility` — risk-adjusted present value to worker
- `company-cost` — expected present value cost to firm
- `CompensationPackage`, `ValuationModel`, `CandidateProfile`, `FirmProfile`
