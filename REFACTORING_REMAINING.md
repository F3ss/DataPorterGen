# Оставшийся план рефакторинга DataPorterGen

Актуально на 2026-09-03. Волны 0–3 исходного `REFACTORING_PLAN.md` выполнены
(коммиты `fe82a24`, `79c4767`, `7dbc959`; затем Волны 2–3 — вне коммитов, по явному запросу).
Ниже — только оставшаяся работа. Правила неизменны: тесты после реализации, границы срезов из
`AGENTS.md`, документация (`README.md`, `GENERATION.md`, примеры) —
в том же изменении; коммиты и переписывание истории — только по явному запросу.

## Уже выполнено (для контекста)

| Волна | Что сделано |
|---|---|
| 0.1 | AGENTS.md синхронизирован с контрактом MERGE source-wins; CLAUDE.md удалён, TDD-журнал выведен из процесса |
| 0.2 | `SecretMasker` отрезает весь query/fragment из логируемых URI |
| 0.3 | `w=0` отклоняется валидаторами; `wasAcknowledged()` на всех путях записи |
| 0.4 | Write-probe перенесён в `PREPARE_TARGET` (после валидации цели, без retry) |
| 0.5 | Типизированный `FailureKind` в `OperationIssue`/отчётах; `ExitCodes` без текстового парсинга |
| 0.6 | Ранний probe report-sink (`prepare()` в портах, exit 2 до подключения) |
| 0.7 | Результаты копирования — в порядке migration plan |
| 1.8 | Guard same-DB: `EndpointNormalizer` + пересечение resolved-топологий в `CONNECT_TARGET` |
| 1.9 | Snapshot fail-closed (POSIX verify / Windows ACL) + предупреждение `CLEANUP_SNAPSHOT` |
| 1.10 | Байт-бюджет: статические доли `maxInFlightMegabytes`/`parallelism`, физические батчи ≤ доли и 32 МиБ, oversized-документ → ошибка до записи (ловится validate-only) |
| 1.11 | Coverage dry-run использует те же координаты batch-уникальных `_id`, что и запись |
| — (пользователь) | `query` для snapshot-шаблонов: `TemplateQuery` + `openTemplateBatches` + запрет `$where`/`$function`/`$accumulator` |
| 2.1 | `Path` убран из domain (`MigrationOptions`/`GenerationOptions`); пути живут в `MigrationProperties` (`migrationReportPath()`/`generationConfigPath()`/`generationReportPath()`); `GenerationSpecReader` — преднастроенный адаптер с `read()` без аргумента |
| 2.2 | generation → migration разорвана: `GenerationSourceInspection` + `GenerationSource.inspect()`; `GenerationModeValidator` на собственных `GenerationTargetMode`/флаге фильтров; контракт `openTemplateBatches(String, TemplateQuery, int)` сохранён |
| 2.3 | `GenerationService` (691 строка) → `GenerationOrchestrator` / `GenerationPreflight` / `GenerationBatchExecutor` (+ `GenerationCounters`); форма отчёта, порядок стадий, golden-тесты и детерминизм split-vs-whole не изменились |
| 2.4 | `MongoGenerationBsonEngine` (507 строк) → `BsonPointerOperations` (JSON-Pointer + кэш токенов) / `BsonRuleEvaluator` (правила, кэш порядка, ThreadLocal SHA-256) / `BsonTemplateInspector` (inspect/idKind); публичный API движка не изменился, golden-значения байт-в-байт |
| 2.5 | Mongo-адаптеры разделены по срезам: `MongoMigrationSource`/`MongoGenerationSource`, `MongoMigrationTarget`/`MongoGenerationTarget`; общие примитивы — `AbstractMongoReader` (+ `checkReadable`/`checkWritable`/`inspectPlan`) и `MongoWriteModels` (package-private) |
| 2.6 | `ArchitectureBoundaryTest`: скан исходников — domain без Spring/Mongo/BSON/Jackson/logging/`java.nio.file`; срезы не импортируют друг друга; `com.mongodb.*` только в `adapters.mongo` (+ зеркальные правила для тестов, integration-тестам разрешено) |
| 3.1 | `.github/workflows/ci.yml`: PR — `test build` (без дубля integration) + отдельный job `integrationTest`; push в main/теги — три Compose-сценария матрицей |
| 3.2 | Dependency locking (`lockAllConfigurations`, `gradle.lockfile` зафиксирован); deprecated-конструктор `MongoWriteException` в тесте заменён трёхаргументным (`Set.of()` меток, драйвер 5.8.1) |

Верификация Волны 2–3: `./gradlew clean test integrationTest build` — успешно;
162 unit-теста зелёные (включая `ArchitectureBoundaryTest`), 22 integration пропущены
(Docker в WSL недоступен; прогоняет CI из 3.1).

## Опциональные оптимизации (после Волн 2–3)

- `ordered(true)` → `ordered(false)` в generation `upsert` (теперь `MongoGenerationTarget`):
  replace/upsert по независимым `_id`, MERGE уже unordered. Оговорка: меняется интерпретация
  ошибок bulkWrite — делать только вместе с integration-тестом (типизация ошибок уже есть —
  `FailureKind`, Волна 0.5).
- Не трогать: ordered `insertMany` в MIGRATE (семантика retry «no retries after write»),
  полный coverage dry-run (это доказательство, семплирование меняет гарантии).

## Замечания по последним изменениям (snapshot `query`)

- Реализация корректна и в границах: `TemplateQuery` — immutable, без типов драйвера в domain;
  конвертация в `BsonDocument` только в `adapters.mongo`; JS-операторы запрещены рекурсивно;
  пустой результат фильтра и некорректный фильтр завершают preflight до target access;
  в логах только `queryApplied=true/false`.
- Коммит `79c4767` содержит фичу snapshot-query, но сообщение описывает старый MERGE-коммит
  («Speed up MERGE with batched source-wins overwrite»). Историю не переписывать; при желании
  отразить реальное содержание в сообщении следующего коммита или заметке.
- `openTemplateBatches` после 2.5 живёт в `MongoGenerationSource` (generation-адаптер источника).

## Проверка каждого среза

```bash
./gradlew test                          # каждый срез
./gradlew integrationTest               # пропущено без Docker — фиксировать как пропущенное
./gradlew clean test integrationTest build   # гейт передачи
```

Compose-гейты — при доступном Docker (или push-джоба CI). Для будущих правок движка/сервиса
обязательны: golden-тесты, тест детерминизма split-vs-whole, сравнение формы отчёта до/после.
