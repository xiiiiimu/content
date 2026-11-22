# Stream → MySQL 批桥接与永久错误隔离契约

2026-11-19 用户确认。保留现有 V23、V22 历史数据和首个请求结果；本轮不提交、推送或合并。

## 正常批

配置 `content.sale.bridge-batch-size`，范围1–200，验收1/20/57。每次最多读取该数量，有几条处理几条，不等待满批。合并活动、reservation/clientKey读取及请求/待发送写入；正常批一次短MySQL事务。事务内检查runtime generation；恢复还检查owner和有效租约。事务失败整批回滚，不ACK；唯一键并发竞争使用新事务重新读取，不能吞掉身份冲突。

HTTP仍只在Redis Lua中预占并XADD后返回PROCESSING。读取、可信绑定读取、ACK、库存归还等Redis操作和MQ发送全部位于MySQL事务外。正常消息在自己的事务提交后才ACK；坏消息拆出，不能使正常批回滚或伪装为正常幂等。

## 可信预占与隔离

Lua在同一次预占中，库存扣减后才按生成的Stream ID保存独立可信完成绑定：原reservationId、账号、活动/素材、clientKey、价格、generation及初始PROCESSING状态。不能根据冲突payload中的reservationId去选择要释放或阻断的对象。正常Stream恢复仍按既有校验落库；可信完成绑定用于严格授权隔离释放，不扩大为所有历史消息的落库前置条件。旧消息无绑定时不得凭冲突payload自动释放库存。

坏消息先在MySQL隔离审计，按原Stream消息ID幂等，保存整个原始body（包含原request字符串）、原因、首次请求引用及可信绑定快照。审计事务失败保留pending；只有审计提交后、对应持久化阻断/入口暂停已生效，才能ACK。提交后ACK丢失通过pending接管重试，不能重复审计或重复归还。

只有可信绑定、当前Redis预占、generation完全一致，且数据库确认没有订单/成交、没有已持久化请求需要另行裁决、没有释放，才允许自动释放孤立预占。数据库持久化reservation fence先于释放提交，阻止桥接/消费者迟到落单；释放Lua再原子核实绑定、当前身份/PROCESSING和释放标记，一次改变状态并归还库存。审计状态更新失败重试也不得再次归还。

已成交、已有请求需要人工裁决或证明不足时，不自动释放。可信reservation fence持久化BLOCKED；已有终态订单仍保留，不改首次结果。未知身份不从payload推断受影响对象，按不可核实范围全局暂停入口及新结算；已知范围暂停相应活动入口。其余合法消息仍可独立持久化。无法确定安全范围时宁可扩大暂停范围，不能错误释放他人或已成交库存。

## 恢复

MySQL保存隔离、fence与未完成释放。Redis丢失后，恢复在既有owner/generation租约下持久化保留消息、重建权威库存，重新安装隔离入口门禁；HOLD不会因为重建而自动解封。消费者先取得reservation fence共享锁，再锁请求，审计取得同一fence排他锁；未知范围隔离取得runtime排他锁，等待已开始的业务事务结束，之后阻止新的结算。

已经确认孤立且释放待完成的预占，在新generation权威重建后按数据库fence/无订单证明记为重建已处理，不向新库存重复加一。释放脚本先标APPLYING，最后才标完成；不完整标记保持HOLD，不能冒充归还成功，也不对客户端暴露已确认拒绝。HOLD孤立预占纳入重建保留量。原reservation仍不能迟到落单。正常混代批中可推进当前代消息，旧代新请求留pending，受控恢复持有owner/lease后才能导入；generation失败不是永久坏消息。人工解除HOLD不属于本轮API范围，不能直接删表/改Redis绕过审计。

## 观测与验收

批次记录实际读取、正常/隔离条数、SQL、提交数与事务/ACK区间，统计真实批大小而非配置值。先RED后GREEN；真实MySQL/Redis覆盖批内重复、clientKey冲突、审计前后故障、ACK丢失、重复释放、已成交目标、generation切换、pending接管与恢复。通过后先验证采集覆盖完整窗口/排空，再同一JAR仅切换批次配置，逐档60/s×60s，新活动/clientKey，前档排空对账后再后档。提交时间缺失输出null，paid_at单列。

支付/退款模拟，FFmpeg容器清理未验证。
