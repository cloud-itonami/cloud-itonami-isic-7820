(ns staffing.report
  "Client billing report rendering — output as a GOVERNED read. The column
  set is not chosen here; it is whatever the StaffingGovernor's
  licensed-disclosure gate approved for the caller's contract tier (see
  `:report/query`). This namespace only renders the approved columns, so a
  disclosure can never exceed the licensed tier."
  (:require [staffing.store :as store]))

(defn render-report
  "Render one assignment's billing report over exactly `columns` (already
  governor-approved). `:worker-id`/`:pay-rate`/`:eligibility-status` are
  only ever rendered when the caller's tier included them."
  [db assignment-id columns]
  (let [asg (store/assignment db assignment-id)
        ts  (last (store/timesheets-of-assignment db assignment-id))
        w   (when (:worker-id asg) (store/worker db (:worker-id asg)))
        cell (fn [col]
               (case col
                 :assignment-id assignment-id
                 :role          (:role asg)
                 :period        (:period ts)
                 :hours         (:hours ts)
                 :approved-amount (:approved-amount ts)
                 :worker-id     (:worker-id asg)
                 :pay-rate      (:pay-rate asg)
                 :eligibility-status (boolean (:eligibility w))
                 :jurisdiction-basis (get-in w [:eligibility :class])
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
