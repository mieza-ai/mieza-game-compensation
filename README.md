# mieza-game-compensation

A five-stage compensation negotiation game for [Mieza](https://mieza.ai) equilibrium solvers. Discovers which compensation structures incentivize effort and retention for different worker types.

## Game Structure

1. **Firm** chooses a compensation menu (set of packages)
2. **Worker** selects a package or rejects all offers
3. **Worker** chooses effort level (period 1)
4. **Worker** decides to stay or leave
5. **Worker** chooses effort level (period 2)

Three mechanisms create strategic tension:
- **Output linkage** — commission scales with output; salary is fixed
- **Vesting/forfeiture** — leaving forfeits unvested equity
- **Effort-value feedback** — period-1 output shifts equity valuation

## Dependencies

Built on the [Mieza Clojure SDK](https://github.com/tacktechai/mieza-clj-sdk) (`ExtensiveFormGame` protocol + compensation domain entities).

## Usage

```clojure
;; deps.edn
{:deps {mieza/game-compensation {:git/url "https://github.com/tacktechai/mieza-game-compensation.git"
                                  :git/sha "..."}}}
```

```clojure
(require '[josh.game.library.compensation-game.core :as comp])
(require '[josh.game.library.compensation-game.schema :as schema])
```

## Running tests

```bash
clj -M:test
```

## License

MIT
