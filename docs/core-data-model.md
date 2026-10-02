# T05 核心数据模型与数据字典

## 验证边界

本任务建立合成数据用的关系结构模板、真实 PostgreSQL 17 约束测试和内部 JDBC 读取投影。没有新增患者或病例 HTTP API，也没有身份认证、业务写服务、临床状态机、审计、幂等、取材、蜡块、玻片或签发功能。存在数据库记录不代表已具备临床使用资格。本工程仍不可接收真实患者数据或直接开放公网。

当前按单医院合成数据场景开发；测试建立第二个合成医院，只为证明外键不会串院。`hospital_id` 是数据归属/一致性边界，不是已完成的授权、多租户隔离或院区模型。

以下两点是待验证的临床假设，不是已确认的业务规范：

- 一份申请可产生多份病例，每份病例当前只能属于一份申请；不支持跨申请合并病例。后续若真实业务不符，必须新增迁移调整
- 病例、容器允许尚未分配业务编号，表示结构草稿。允许存储这样的行，不表示允许接收、临床流转、出具报告或其他临床操作；这些条件由后续经过确认的业务流程建立

V1 只初始化 schema，V2 为增量建表。V2 不插入医院、来源、科室、患者或临床编号配置；示例记录仅由测试在随机 schema 中生成。

## 标识和编号原则

1. `id` 是内部 UUID 主键，默认使用 PostgreSQL `gen_random_uuid()`。实体关系全部引用内部 UUID，不引用姓名、身份证号、就诊号、申请号或病理号
2. 外部业务编号用文本保存，保留前导零与大小写；没有自动大小写折叠、Unicode 归一化、患者匹配或合并。未知的患者姓名/生日保持 NULL，不生成“默认患者”、默认身份或默认性别
3. 每个编号都有明确范围。来源内编号使用 `hospital_id + source_system_id`；病例/容器使用 `hospital_id + number_namespace`。`number_namespace` 是明确传入的编号作用域，不是固定的年度、病种前缀或条码标准；后续业务入口必须依据医院确认的配置解析它，不能让任意输入绕过编号冲突检查
4. `source_system` 表示本医院认可的编号来源。导入时应映射到真正的编号来源，而不是随意使用传输接口名。例如由中间件转送的 HIS 编号不能自动视为中间件的新编号域。跨来源同号不自动认定为同一患者
5. 编号及代码禁止空串和首尾 POSIX 空白。代码/namespace 上限 128 字符，业务编号/显示名称/标签上限 255 字符；这是当前技术边界，不是临床编码标准。编号缺失使用 NULL，不使用空串
6. 病例/容器的 namespace 与 number 必须同时为空或同时非空；非空组合唯一。多个未编号草稿允许并存
7. 本任务没有临床编号分配器，不使用 `MAX(...) + 1`，不承诺连续或无间隙编号。后续编号分配要在明确范围内采用数据库序列或事务安全机制，并保留唯一约束
8. 更正业务编号不需要改变内部实体 ID；测试验证更正编号后原就诊/申请/病例/容器关系保持不变。正式更正、身份合并与留痕流程尚未实现

## 关系

- hospital → source_system、department、patient 均为一对多
- patient → patient_identifier、encounter、pathology_request 均为一对多
- 申请必须关联患者，可不关联就诊；一旦指定就诊，该就诊必须属于同一医院的同一患者
- 申请 → 病例、容器均为零到多。病例与容器不复制患者姓名、身份证等身份字段；通过申请追溯患者
- 容器必须属于申请，可暂不分配病例；若分配病例，病例必须属于同医院、同申请
- `requesting_department_id` 只是申请科室，不等于病理科室、病例负责科室、可访问科室或签发资格
- 容器是提交的物理标本容器，不是蜡块、玻片或数字切片。标签和采集/接收时间可缺失，不规定固定液、部位词典或物流状态

数据库使用含 `hospital_id` 的复合外键保证上述一致性。申请到就诊的外键为 `(hospital_id, patient_id, encounter_id)`；容器到病例为 `(hospital_id, request_id, case_id)`。这些可空关系采用默认 `MATCH SIMPLE`，独立的非空患者/申请外键仍然有效；不能误改成使“只缺可选 ID”失败的 `MATCH FULL`。

所有实体外键都使用 `ON DELETE RESTRICT`，不会因删除患者或申请自动级联清除临床结构。这不是留存制度、删除授权或禁止一切直接 SQL 删除；本任务没有实现业务删除入口，也不设置保存年限或清理作业。

## 公共字段

下列字段出现在全部 9 张表；`hospital` 本身不含 `hospital_id`，其余表的该字段都必填。

| 字段 | SQL 类型 | 可空/默认 | 语义 |
| --- | --- | --- | --- |
| id | uuid | 非空，gen_random_uuid() | 内部主键；不编码临床信息 |
| hospital_id | uuid | 非空，无默认 | 所属医院；FK → hospital.id |
| version | bigint | 非空，0 | 乐观并发修订号，CHECK >= 0；不是临床报告版本 |
| created_at | timestamptz | 非空，CURRENT_TIMESTAMP | 技术记录创建时间 |
| updated_at | timestamptz | 非空，CURRENT_TIMESTAMP | 技术记录最后修改时间；写入方负责更新 |

`timestamptz` 表示时间点，JDBC 投影转换为 Java `Instant`。显示时区由后续界面决定；不拿服务器本地无时区时间代替时间点。出生日期使用 `date`，不假造日内时间。技术时间与可空业务事件时间分开。

### hospital

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| code | text，非空 | 全库唯一医院代码；没有默认医院 |
| name | text，非空 | 医院显示名称 |

唯一约束：`code`。本表不表示院区，不保存账号、权限或机构执业信息。

### source_system

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| code | text，非空 | 医院内来源代码 |
| name | text，非空 | 来源名称 |

唯一约束：`(hospital_id, code)`、供 FK 引用的 `(hospital_id, id)`。不保存接口凭据或地址；没有来源即不自动创建“未知”来源。

### department

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| code | text，非空 | 医院内科室代码 |
| name | text，非空 | 科室显示名称 |

唯一约束：`(hospital_id, code)`、`(hospital_id, id)`。外院、院区、外部科室映射、科室层级和权限归属留待后续确认。

### patient

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| display_name | text，可空 | 最小患者显示信息；不是身份匹配键 |
| birth_date | date，可空 | 出生日期；未知保持 NULL |

唯一约束：`(hospital_id, id)`。本模型不据姓名/生日去重，不采集证件原件、联系方式或性别字典，不实现主索引合并。真实身份校验规则后续明确。

### patient_identifier

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| patient_id | uuid，非空 | 同医院 patient |
| source_system_id | uuid，非空 | 同医院 source_system |
| identifier_namespace | text，非空 | 来源内标识命名域；示例测试仅用 synthetic-mrn |
| identifier_value | text，非空 | 来源分配的标识文本 |

唯一约束：`(hospital_id, source_system_id, identifier_namespace, identifier_value)`，同一标识不能同时指向两个患者。患者可以没有标识，也可以有多个来源标识；历史标识、失效/复用和合并机制尚未实现。

### encounter

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| patient_id | uuid，非空 | 同医院患者 |
| source_system_id | uuid，非空 | 同医院就诊编号来源 |
| encounter_number | text，非空 | 来源内就诊编号，不是患者主键 |
| department_id | uuid，可空 | 就诊关联科室；须同医院 |
| occurred_at | timestamptz，可空 | 来源就诊事件时间；未知不填当前时间 |

唯一约束：`(hospital_id, source_system_id, encounter_number)`、供患者一致性 FK 引用的 `(hospital_id, patient_id, id)`。不设置门诊/住院枚举或就诊状态流程。

### pathology_request

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| patient_id | uuid，非空 | 申请对应的同医院患者 |
| encounter_id | uuid，可空 | 若有就诊，必须同患者、同医院 |
| source_system_id | uuid，非空 | 同医院申请编号来源 |
| request_number | text，非空 | 来源内申请编号 |
| requesting_department_id | uuid，可空 | 申请科室；同医院；不用于推导授权 |
| requested_at | timestamptz，可空 | 申请事件时间 |

唯一约束：`(hospital_id, source_system_id, request_number)`、`(hospital_id, id)`。本地人工录入的来源与申请编号策略待业务入口确认；T05 不自动生成来源或业务编号。

### pathology_case

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| request_id | uuid，非空 | 所属同医院申请；当前暂定单申请 |
| number_namespace | text，可空 | 病例编号域，须与 case_number 同空/同非空 |
| case_number | text，可空 | 病例业务编号；空值只表示结构草稿 |

唯一约束：`(hospital_id, number_namespace, case_number)`、`(hospital_id, request_id, id)`。不加入固定病理号模板、状态、诊断、报告或签发字段。

### specimen_container

| 字段 | 类型/可空 | 说明 |
| --- | --- | --- |
| request_id | uuid，非空 | 所属同医院申请 |
| case_id | uuid，可空 | 若指定，必须与容器属于同一申请和医院 |
| number_namespace | text，可空 | 容器编号域，须与 container_number 同空/同非空 |
| container_number | text，可空 | 容器业务编号；尚未确认条码制式 |
| label | text，可空 | 简短容器标签；不替代取材描述 |
| collected_at | timestamptz，可空 | 采集时间 |
| received_at | timestamptz，可空 | 接收时间 |

唯一约束：`(hospital_id, number_namespace, container_number)`。采集/接收顺序、来源时间纠错和不完整历史数据接纳规则未知，因此本任务不把这些临床时序锁成 CHECK。

## 乐观版本和读取骨架

`CoreModelQueries` 是 Spring JDBC 的内部只读 Repository，提供按医院+ID读取患者、就诊、申请、病例，以及按医院+申请读取容器列表。它没有 HTTP 暴露，也不返回可绕过医院参数的全表列表；但医院参数本身不构成身份认证或授权。后续调用层仍必须鉴权和限制资源范围。

读取投影包含 `version`。未来的业务写入必须采用参数化、单条原子条件更新，例如：

```sql
UPDATE patient
SET display_name = :new_name,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE hospital_id = :hospital_id
  AND id = :id
  AND version = :expected_version;
```

受影响 1 行表示成功，0 行表示记录不存在、范围不匹配或版本已变化；由后续授权服务安全地处理冲突，不能无条件重试覆盖新值。关联修改需要事务。`CoreModelVersionTest` 使用两个独立数据库连接先读取同一版本，再验证第一写入成功、旧版本写入为 0、胜出值保持。

边界：只有 `version` 字段与非负 CHECK 并不能强制所有 SQL 都遵守乐观锁；T05 没有通用写入口或自动版本/时间触发器，也不声称已实现所有业务并发控制。正式写服务必须落实上述契约并补接口级并发测试；不要用 `xmin` 充当长期业务修订号。

## 索引

主键/唯一约束建立对应唯一索引。关系读取与外键检查还覆盖：

- 患者标识：`(hospital_id, patient_id)`
- 就诊：唯一索引的 `(hospital_id, patient_id, id)` 前缀支持按患者检索；另有 `(hospital_id, department_id)`
- 申请：`(hospital_id, patient_id, encounter_id)`、`(hospital_id, requesting_department_id)`
- 病例：唯一索引的 `(hospital_id, request_id, id)` 支持按申请检索
- 容器：`(hospital_id, request_id, case_id)` 支持按申请及同申请病例检索
- 来源关系的唯一编号索引以 `(hospital_id, source_system_id)` 开头

这些是当前关系路径的基础索引，不声称完成大规模查询性能验收。后续新增查询应使用真实分布的合成数据分析执行计划。

## 测试和迁移验收

沿用 [数据库开发说明](database-development.md) 的 PG17 测试服务，执行完整 `./backend/mvnw -B -f backend/pom.xml verify`。不用 H2，缺数据库时不跳过。测试创建随机 `pis_test_*` schema，只清理自己创建的 schema。

覆盖：

- 空 schema 执行生产 V1+V2，两次执行第二次无操作；Flyway 校验成功
- 从真实生产 V1 升级到 V2，保留 V1 checksum/历史和合成 marker 数据，无患者种子；与 T04 的独立 synthetic upgrade-fixture 区分
- 五实体独立、同申请多病例/多容器、无就诊申请、未分配病例容器及多个未编号结构草稿
- 医院/来源/科室代码及全部业务编号同域重复拒绝；跨医院、跨来源/namespace允许同文本，前导零和大小写保留
- 跨医院的患者/来源/科室/申请/病例引用拒绝；同院错患者就诊、容器错申请病例在新增和更新时都拒绝
- 缺失父记录、必填关系为空、删除仍被引用的父记录拒绝；无级联删除
- 空白/首尾空白编号、缺半个编号对、负/空版本拒绝；UUID、版本、技术时间默认值可读
- 业务编号更正不改变 UUID 关系；双连接验证旧版本原子条件更新不能覆盖胜出值
- JDBC 映射、时间点转换、可空字段、错误医院和缺失ID返回空结果

未跑真实 PG 或完整 CI 时只能报告“已编译/待执行”，不能以测试源码存在、静态审查或单个用例通过代替全套成功。当前测试不覆盖临床正确性、实际医院编号规范、生产权限或留存合规。

## 后续必须确认的业务项

一申请拆多病例/跨申请合并是否存在；患者主索引及标识更正/合并；来源编号大小写、复用与回收规则；医院/院区/外院/科室映射；编号域配置和事务安全分配；采集/接收时间来源与纠错；人口学词典；数据留存与删除授权。未经确认，不提前固化枚举、病理号模板或保存期限。

技术依据：[PostgreSQL 17 约束说明](https://www.postgresql.org/docs/17/ddl-constraints.html)、[时间类型](https://www.postgresql.org/docs/17/datatype-datetime.html)、[UUID 函数](https://www.postgresql.org/docs/17/functions-uuid.html)。
