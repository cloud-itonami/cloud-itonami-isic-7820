# Temp-Staffing Actor Design — TempStaffing-LLM as a contained intelligence node

Randstad / Adecco / ManpowerGroup 級の一時労働者派遣業を、agency-as-employer-
of-record の運用で、SaaS課金に依存せず OSS の actor として自前運用するための
設計。`cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で封じ
込めた「収集・保持・配信」構図)を、労働者派遣ドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

配置の起案・タイムシートからの給与額計算・レポート列の提案は LLM で加速
できる。しかし LLM は次の理由で**配置・延長・承認・紛争解決の最終権限を
持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| worker の eligibility を出典なしに「確認済み」と断定 | 無資格就労の助長 |
| 累計継続期間が法定上限を超えたまま延長を提案 | 転換権/均等待遇義務の違反 |
| 残業割増を忘れた/誤った給与計算 | 賃金未払い(wage theft) |
| hazardous-duty 配置を高確信のまま自動処理 | 労災リスクの見落とし |

したがって設計課題は「LLM で派遣業務を回す」ことではなく、**「LLM を信頼
境界の内側に封じ込め、eligibility・tenure cap・wage floor・人間レビューの
層をどう被せるか」**である。

## 2. アクター・トポロジ(監督ツリー)

```
StaffingSystem (root supervisor)
│
├── PlacementActor ……… worker×client の新規配置(:assignment/place)
├── ExtensionActor ……… 既存配置の延長(:assignment/extend、tenure-limit-gate の主戦場)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; TempStaffing-LLM 封じ込め ★
│     ├── TempStaffing-LLM (sealed)  proposal only(src/staffing/llm.cljc)
│     ├── StaffingGovernor           INDEPENDENT ゲート(src/staffing/policy.cljc)
│     ├── Committer                  SSoT/台帳への書き込み(src/staffing/store.cljc)
│     └── Recorder                    監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(hazardous-duty 配置・異議申立ての interrupt を受ける)
└── BillingActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **TempStaffing-LLM は最下層ノードで、台帳・開示経路に直接触れない。**
   出力は常に StaffingGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(配置/延長/承認/
   開示しない)** に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの eligibility/tenure/wage
   基準で行ったか」は監査台帳への Datalog クエリ — 監査・労働紛争が同一
   ファクトログから出る。

## 3. OperationActor 内部(TempStaffing-LLM ラッパー)

`src/staffing/operation.cljc` の langgraph StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

チャネル: `:request :context :proposal :verdict :disposition :record :approval :audit`

- **`:context` は外部注入**(`{:actor-id .. :actor-role .. :tenant .. :phase ..}`)。
- **`:govern` は TempStaffing-LLM と別系統**(eligibility クラス表 + tenure
  cap 表 + wage floor 参照 + 契約 tier 表)。LLM 提案を*拒否*して hold に
  substitute できる。
- **`interrupt-before #{:request-approval}`** で実際の人間レビューへ。

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`staffing.store/Store` プロトコル): `MemStore`(既定)/
  `DatomicStore`(`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約
  テストで等価性を保証。
- **Advisor**(`staffing.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は confidence 0
  の noop に落ち、LLM 不調が auto-commit にならない。
- **Phase**(`staffing.phase`、context の `:phase 0..3`): 段階導入。
  read-only → assisted → supervised-auto。governor より保守的にしか働かない。
  **`default-phase` は最も保守的な 1**(context に `:phase` が無い呼び出しが
  最大権限を得てしまう fail-open を避けるため。`cloud-itonami-isic-6311`
  等で同型のバグが見つかり修正された教訓を踏襲)。**`:dispute/request` は
  どの phase の `:auto` にも入らない**(恒久ゲート)。

## 4. StaffingGovernor(独立検閲層)

`src/staffing/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に
判定する。

```clojure
(policy/check request context proposal store)
;; => {:ok? bool :violations [..] :confidence c :escalate? bool :hazardous? bool :dispute? bool}
```

判定の優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC** — `permissions` 表で `actor-role × operation` を引く。
2. **eligibility-gate** — worker の eligibility 出典が
   `staffing.facts/allowed-eligibility-classes` に無ければ HARD violation。
   `:operator-verified-eligibility` は加えて `:verification-ref` を要求。
3. **tenure-limit-gate**(新規、staffing 固有) — 同一 worker×client の
   累計継続期間がその法域の法定上限(`staffing.facts/tenure-cap-months`)を
   超えたら HARD violation。USA はエントリ自体が無い(一般連邦上限なし、
   捏造禁止)。
4. **wage-compliance-gate** — タイムシート承認の実効時給が
   `staffing.store/wage-floors`(operator 管理)の最低ラインを下回れば
   HARD violation。wage-floor 未登録の法域は承認そのものが HARD 拒否。
5. **licensed-disclosure** — `:report/query` は Store 登録済みの有効な
   契約(tenant×tier)を要求し、提案列が契約 tier を超えたら HARD violation。
6. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
7. **high-risk-assignment gate** — 配置が hazardous-duty → 必ず人間承認
   (soft)。
8. **dispute-request** — `:dispute/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/staffing/store.cljc`。dev は in-mem の EDN 事実層(本番は Datomic)。

- **entities**: `workers` `clients` `assignments`(worker×client、
  tenure-limit-gate の対象) `timesheets`(wage-compliance-gate の対象)
  `wage-floors`(operator 管理の参照レート、捏造禁止) `contracts`
  (billing licensing)。
- **commit-record!**: 操作結果を SSoT に反映(`:report-serve` は SSoT
  変更なし — 台帳のみ)。
- **append-ledger!**: 全 commit/reject/開示を**不変台帳**に積む。

## 6. 開示(governed read)

`src/staffing/report.cljc`。`render-report` は StaffingGovernor が承認した
列のみを出力する。列ポリシーはコードで固定される。

## 7. デモ(`clojure -M:dev:run`)

`src/staffing/sim.cljc` が7操作を actor に通す(§sim.cljc docstring 参照):
クリーンな配置(USA) → commit、eligibility 未登録の配置 → hold、JPN 36ヶ月
上限超過の延長 → hold、残業割増を欠いた給与計算 → hold、tier超過のレポート
→ hold、hazardous-duty 配置 → 人間承認 → commit、異議申立て → 常に人間承認
→ commit。

## 8. テスト(`clojure -M:dev:test`)

`test/staffing/policy_contract_test.clj` が**ガバナンス契約を実行可能**に
する。`test/staffing/phase_test.clj` が段階導入と「異議申立ては恒久的に
人間専用」「:phase 省略時に最大権限が付与されない」ことを保証。
`test/staffing/facts_test.clj` が統計カタログ自体の正直さ(捏造禁止、USA に
架空の tenure cap を持たない)を保証。

## 9. 実装と業態の対応(Randstad/Adecco/ManpowerGroup → temp-staffing actor)

| 実在業態の機能 | temp-staffing actor での実体 |
|---|---|
| 労働者名簿・資格確認 | `store` workers + eligibility-gate |
| 配置/派遣先管理 | `store` assignments + `:assignment/place` |
| 派遣期間の法定上限管理 | tenure-limit-gate(JPN/DEU/GBR の実在制度) |
| タイムシート/給与計算 | `store` timesheets + wage-compliance-gate |
| 労災/危険作業への配慮 | high-risk-assignment gate |
| 派遣先への請求レポート | `report/render-report`(tier 列限定の governed read) |
| 労働紛争・異議申立て | `:dispute/request`(恒久 human-only) |
| アクセス権限・契約 | StaffingGovernor RBAC 表 + `contracts` |
| (SaaS/従来ベンダーと同型)監査台帳 | `store` append-only ledger |
