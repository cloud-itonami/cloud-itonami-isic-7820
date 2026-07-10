# ADR-0001: cloud-itonami-isic-7820 — TempStaffing-LLM を封じ込めた知能ノードとする一時労働者派遣アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-8291`(dossier、収集・
  保持・契約者限定開示パターンの原型)、`cloud-itonami-isic-7810`(一時金型
  の人材紹介業、本 actor が構造的に区別する隣接業態)、robotaxi-actor
  ADR-0001(研究モデルを信頼境界に封じ込める actor 設計)、langgraph-clj
  ADR-0001(Pregel superstep + interrupt + Datomic checkpoint)
- 文脈: com-junkawasaki/root superproject ADR-2607111600(本 ADR の対、
  経緯・スコープ決定の全文はそちら)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから ISIC Rev.4
7820「Temporary employment agency activities」を選定した。既に実装済みの
`cloud-itonami-isic-7810`(Community Employment Agency、一時金型の人材
紹介業 — agency は worker の employer of record にならない)とは業態が
本質的に異なる: 7820 の派遣業では**agency 自身が worker の雇用主
(employer of record)** であり続け、worker を client 企業へ time-bounded
な assignment として派遣する。この構造的差異が、7810 には存在しない
一連の規制関心(累計継続期間の法定上限、タイムシートベースの賃金遵守)を
生む。死んだ `gftdcojp/cloud-itonami-N7820` プレースホルダー URL のまま
`:spec` で放置されていたスロットである。

配置の起案・タイムシートからの給与額計算・レポート列の提案には LLM が
有効だが、**LLM に配置・延長・承認・紛争解決の最終権限を直接持たせるのは
危険**である(出典なき eligibility 確認=無資格就労助長、法定上限超過の
延長=転換権/均等待遇義務違反、給与計算誤り=賃金未払い)。したがって設計
課題は「LLM で派遣業務を回す」ことではなく、**「LLM を信頼境界の内側に
封じ込め、eligibility・tenure cap・wage floor・人間レビューの層をどう
被せるか」**である。

## 決定

新規 actor `cloud-itonami-isic-7820`(ISIC Rev.4 7820)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-6311`
の直接の手本を踏襲しつつ、労働者派遣固有のリスク面(法定継続期間上限、
賃金遵守)に対応する2つの新規 HARD チェックを追加した。

### 1. TempStaffing-LLM ⊣ StaffingGovernor(単一不変条件)

> **TempStaffing-LLM は、StaffingGovernor が拒否する配置(`:assignment/
> place`)・延長(`:assignment/extend`)・タイムシート承認
> (`:timesheet/approve`)・開示(`:report/query`)・異議解決
> (`:dispute/request`)を決して行わない。**

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | eligibility-gate | HARD | worker の eligibility 出典が許可クラスに無ければ拒否。`:operator-verified-eligibility` は加えて `:verification-ref` を要求 |
| 3 | **tenure-limit-gate**(新規、staffing 固有) | HARD | 同一 worker×client の累計継続期間がその法域の法定上限を超えたら拒否。確信度に関わらず。他の cloud-itonami actor に存在しない、労働者派遣という業態固有のリスク面 |
| 4 | **wage-compliance-gate**(新規、staffing 固有) | HARD | タイムシート承認の実効時給が operator 管理の wage-floor を下回れば拒否。wage-floor 未登録の法域は承認自体を拒否 |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | high-risk-assignment gate | SOFT | 配置が hazardous-duty → 必ず人間承認 |
| 8 | dispute-request | SOFT(無条件) | 労働紛争は確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 給与の実際の支払い実行(振込・源泉徴収)は一切
含まない — この actor は承認された金額を*計算・記録*するのみで、資金を
一切動かさない(`cloud-itonami-isic-6311` が一切トレードしないのと同型の
構造的除外)。

### 2. Phase 0→3 + 恒久人間ゲート + fail-open 修正の反映

`default-phase` は**最も保守的な 1** に設定した。`:phase` を context に
含めない呼び出しが最大権限(auto-commit 可能な phase 3)を得てしまう
fail-open は、本セッション中に `cloud-itonami-isic-6311` の sibling
template で実際に見つかり修正された既知のバグパターンであり、本 actor は
最初からこの安全な既定値を採用した(§3 参照)。`:dispute/request` はどの
phase の `:auto` 集合にも入らない構造的恒久ゲート。

### 3. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/staffing/facts.cljc`)は実在する3つの tenure-cap 法規
(JPN 労働者派遣法第40条の2 の3年上限、DEU AÜG §1 Abs.1b の18ヶ月上限、
GBR Agency Workers Regulations 2010 reg.5 の12週均等待遇適格期間)+
2つの wage-compliance 法的根拠(USA FLSA、JPN 最低賃金法)+ 1つの実在
eligibility 様式(USA Form I-9)+ 1つの構造的クラス
`:operator-verified-eligibility`。**実際の現行の最低賃金額は一切ハード
コードしない** — 法的根拠の引用のみ実在させ、現行レートは
`staffing.store/wage-floors`(operator 管理)に置く。USA は意図的に
tenure-cap エントリを持たない(一般的な連邦上限が実在しないため、捏造
しない)。`facts/coverage` が常に正直に現状を報告する。

### 4. Robotics premise: false

配置・延長・タイムシート承認・レポート開示はすべて書面/システム上の
業務であり、actor の境界の外に物理的な作動(労働そのもの)は存在する
ものの、actor 自身がそれを実行することはない。

## Consequences

- (+) `kotoba-lang/industry` registry の 7820 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-N7820` URL)から実装へ昇格(`M6910`・`isic-8291`・
  `isic-4690`・`isic-4610`・`isic-6311` に続く6件目)。
- (+) tenure-limit-gate と wage-compliance-gate という、他の cloud-itonami
  actor に存在しない labor 固有の HARD チェックを2つ新設し、7810(一時金型
  人材紹介)からの安易な流用ではなく、agency-as-employer-of-record という
  業態の構造的差異を反映したことを ADR に明記した。
- (+) `default-phase` を最初から保守的な値(1)に設定し、他 actor で見つ
  かった fail-open バグを未然に回避した。
- (+) `clojure -M:dev:test`: 全テストパス。`clojure -M:lint`: エラー0・
  警告0。`clojure -M:dev:run` デモも end-to-end で確認済み(7シナリオ全て
  正しく発火)。
- (-) R0 の tenure-cap カバレッジは3法域のみ(JPN/DEU/GBR)。USA は意図的
  にカバレッジ外(捏造禁止)。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の
  eligibility ベンダー統合・payroll システム連携は operator の責任範囲。

## 代替案と不採用理由

- **`cloud-itonami-isic-7810` のスロットを拡張して流用**: 7810 は
  agency が employer of record にならない一時金型モデルであり、
  tenure-limit-gate/wage-compliance-gate という本業態固有のリスク面が
  構造的に存在しない。既存実装を拡張するより、独立業態として 7820 を
  新設する方が正確。
- **LLM に配置・承認権限を直接付与(エージェント自律)**: 速いが、
  無資格就労・法定上限超過・賃金未払いを構造的に防げない。単一不変条件
  (決定1)に反する。
- **tenure-limit-gate/wage-compliance-gate を SOFT(escalate)にとどめる**:
  法定上限超過や賃金未払いは確信度と無関係に起きるため、SOFT では低確信
  フィルタをすり抜ける高確信の違反を止められない。HARD が必須と判断した。
