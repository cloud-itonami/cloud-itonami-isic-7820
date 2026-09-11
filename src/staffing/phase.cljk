(ns staffing.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-6311`'s rollout phases: start narrow (read-only),
  widen as trust grows. Where the StaffingGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have
  *yet*?'. It can only ever make the actor MORE conservative than the
  governor: it downgrades a governor-clean commit to approval or hold,
  never the reverse.

    Phase 0  read-only          — no writes at all. `:report/query` only
                                  (still governor-gated).
    Phase 1  assisted-placement — `:assignment/place` allowed, every
                                  placement needs human approval.
    Phase 2  + extend/timesheet — adds `:assignment/extend`,
                                  `:timesheet/approve` and
                                  `:dispute/request` (still approval-only).
    Phase 3  supervised auto    — governor-clean, high-confidence
                                  `:assignment/place`/`:assignment/extend`/
                                  `:timesheet/approve` may auto-commit.

  `:dispute/request` is deliberately NEVER a member of any phase's `:auto`
  set, at any phase — a worker/client dispute always reaches a human,
  independent of the StaffingGovernor's own always-escalate check on the
  same op.

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it.")

(def read-ops  #{:report/query})
(def write-ops #{:assignment/place :assignment/extend :timesheet/approve :dispute/request
                 :candidate/intake :worker/hire :worker/decline})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:dispute/request`, `:worker/hire` and
  `:worker/decline` are intentionally absent from every phase's `:auto`
  set. `:candidate/intake` (recording that someone is a candidate, from a
  direct application or a human-carried referral draft) IS auto-eligible at
  phase 3, because recording how someone arrived is not a decision about
  them."
  {0 {:label "read-only"           :writes #{}
                                    :auto #{}}
   1 {:label "assisted-placement"  :writes #{:assignment/place :candidate/intake}
                                    :auto #{}}
   2 {:label "assisted-extend"     :writes #{:assignment/place :assignment/extend
                                              :timesheet/approve :dispute/request
                                              :candidate/intake :worker/hire :worker/decline}
                                    :auto #{}}
   3 {:label "supervised-auto"     :writes #{:assignment/place :assignment/extend
                                              :timesheet/approve :dispute/request
                                              :candidate/intake :worker/hire :worker/decline}
                                    :auto #{:assignment/place :assignment/extend :timesheet/approve
                                            :candidate/intake}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (staffing.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase number (`(get
  phases phase (get phases default-phase))`). This is directly reachable
  by any ordinary caller that simply omits :phase — not just malformed/
  malicious input — so it must be the MOST CONSERVATIVE phase, never the
  most permissive (a live check on the `cloud-itonami-isic-6311`/
  `gftd-talent-actor` sibling templates this session found the same
  fail-open shape: a caller who forgets :phase silently got maximum
  autonomy instead of the safe default). `:dispute/request` is unaffected
  either way — never in any phase's `:auto` set."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:report/query`) pass through unchanged (phase restricts write
    autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:dispute/request` is never
    auto-eligible at any phase, so it always lands here once phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a StaffingGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
