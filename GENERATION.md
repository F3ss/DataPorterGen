# Генерация данных

[← Основной README](README.md)

Этот документ описывает режим `GENERATE`: как запустить его, как устроены шаблоны и как использовать каждый вид правила генерации.

## Самый короткий ответ: статическая строка

Статическое значение задаётся правилом `literal`. Например, чтобы записать строку `ACTIVE` в поле `status` каждого сгенерированного документа:

```yaml
fields:
  /status:
    kind: literal
    value: "ACTIVE"
```

Если `status` уже есть в исходном шаблоне, значение будет заменено. Если поля нет, оно будет добавлено. Кавычки вокруг строки рекомендуются: так YAML не интерпретирует значения вроде `true`, `123` или `2026-01-01` как другой тип.

Минимальный полный конфиг:

```yaml
version: 1
seed: 42
templateSelection: SHUFFLED_CYCLE

collections:
  - name: customers
    count: 10
    fields:
      /_id:
        kind: randomString
        alphabet: ALPHANUMERIC
        length: 12
      /status:
        kind: literal
        value: "ACTIVE"
```

Для этого примера коллекция `customers` должна:

- существовать в исходной БД и содержать хотя бы один документ-шаблон;
- уже существовать в целевой БД;
- допускать вставку документа с полями исходного шаблона, новым `_id` и `status`.

## Как запустить генерацию

```bash
export MIGRATION_MODE=GENERATE
export SOURCE_MONGODB_URI='mongodb://host:27017'
export SOURCE_DATABASE='catalog'
export TARGET_MONGODB_URI='mongodb://host:27017'
export TARGET_DATABASE='catalog'
export GENERATION_CONFIG_PATH='./generation.yml'
export GENERATION_VALIDATE_ONLY=false
export GENERATION_ALLOW_UNPROVEN_IDS=false
export MIGRATION_REPORT_PATH='reports/generation-report.json'

java -jar build/libs/DataPorterGen.jar
```

Сначала рекомендуется выполнить тот же запуск с `GENERATION_VALIDATE_ONLY=true`. В этом режиме приложение читает источник и цель, проверяет правила, `_id`, unique-индексы и выполняет пробную генерацию, но не проверяет возможность записи и не добавляет документы.

`GENERATE` работает как UPSERT по точному `_id`. Если такого `_id` в цели нет, документ вставляется. Если `_id` уже существует, целевой документ полностью заменяется, поэтому старые поля, отсутствующие в новом документе, удаляются. Коллекции, индексы и views не создаются и не удаляются; `DROP_AND_RECREATE`, `MERGE` и непустые `include-collections`/`exclude-collections` в этом режиме запрещены.

## Как используются исходные документы

Генерация не создаёт документ с нуля. Для каждой настроенной коллекции приложение:

1. Применяет необязательный MongoDB `query` коллекции и читает совпавшие исходные документы в стабильном порядке `_id` ограниченными batch-пакетами.
2. Сохраняет во временный raw-BSON snapshot максимальный префикс совпавших документов, помещающийся в долю коллекции.
3. Выбирает шаблон согласно `templateSelection`: по умолчанию — в воспроизводимо перемешанном цикле без повторов, либо последовательно через `i % количество_шаблонов`.
4. Сохраняет неуказанные поля согласно per-collection настройке `unconfiguredFields` (по умолчанию `SNAPSHOT` — значения и BSON-типы копируются из шаблона).
5. Применяет правила из `fields` и вставляет либо полностью заменяет результат в цели по `_id`.

Поэтому пустой `fields: {}` означает «скопировать шаблон без заданных преобразований», но `_id` всё равно должен быть безопасно разрешён.

Необязательный `unconfiguredFields` коллекции задаёт, что происходит с полями шаблона, отсутствующими в `fields`. Для всех не-`SNAPSHOT` режимов определён общий keep-набор путей, которые всегда сохраняют реальные значения шаблона: сами настроенные пути из `fields`, `/_id`, цели локальных `ref`/`dateTime ref` (в том числе читаемые напрямую из шаблона), цели межколлекционных `ref` из других коллекций (например, `/_id` коллекции `customers`, когда `orders./customerId` ссылается на него, а в конфиге `customers` он не настроен) и поле-источник автоопределённого `_id` (FIELD_REFERENCE).

- `SNAPSHOT` (по умолчанию) — значения и BSON-типы неуказанных полей копируются из шаблона; вывод байт-в-байт совпадает с конфигом без настройки.
- `OMIT` — документ вместо полной копии шаблона получает только ключи keep-набора. Пустой `fields: {}` в этом режиме означает «только `_id`».
- `DEFAULTS` — все поля шаблона присутствуют, но ненастроенные получают дефолтное значение своего BSON-типа: числа (int32/int64/double/`Decimal128`) — `0`, строки — `""`, `false`, даты и таймстампы — epoch `0`, `ObjectId` — полностью нулевой, binary — пустой, `null` — `null`, ненастроенные массивы — `[]`; вложенные документы сохраняют все ключи, а их листья дефолтятся.
- `RANDOM` — все поля присутствуют, ненастроенные получают воспроизводимое случайное значение той же «размерности», что в шаблоне: строка той же длины (алфавит `A–Z a–z 0–9`), целое — той же разрядности с сохранением знака и BSON-типа, double/`Decimal128` — та же разрядность целой части и то же число знаков после запятой, дата/таймстамп — та же разрядность epoch-millis, binary — той же длины, `ObjectId` — детерминированно из seed, массив — той же длины с рандомизацией каждого элемента по его типу и размеру, вложенные документы рекурсивны; `null`/MinKey/MaxKey не меняются, regex получает случайный паттерн той же длины с теми же флагами. Случайность выводится из `(seed, collection, iteration, path)` как весь остальной рандом — повторный запуск с тем же seed даёт тот же результат.

Массив сохраняется целиком с реальными значениями, если в keep-набор попадает любой путь внутрь него — в любом режиме. Источник и цель могут совпадать: snapshot-префиксы всех коллекций завершаются до первой записи, поэтому документы, записанные текущим запуском, не становятся новыми шаблонами.

Полезный snapshot-бюджет равен 99% от `maxWorkingMegabytes`. Он целочисленно делится на одинаковые доли между коллекциями; остаток и неиспользованная часть доли не передаются другим коллекциям. В доле учитываются raw BSON, 4 байта length-prefix и 8 байт offset-индекса для каждого документа. Первый непомещающийся документ не записывается, после чего cursor закрывается и приложение переходит к следующей коллекции. Пустая коллекция или первый шаблон, не помещающийся в долю, завершает preflight до подключения к цели и удаляет временный snapshot. Каталог и файлы snapshot создаются с правами только для владельца: на POSIX это проверяется повторным чтением прав, на Windows применяются owner-only ACL; если безопасность подтвердить нельзя, запуск завершается ошибкой до чтения документов. Ошибка удаления временного snapshot отражается в отчёте предупреждением `CLEANUP_SNAPSHOT`, а не игнорируется. При усечении анализ `_id` и validate-only coverage выполняются только по сохранённому префиксу, о чём появляется warning в отчёте.

Генерация не атомарна, не имеет rollback или resume. После неудачной записи состояние цели нужно проверить вручную.

`GENERATION_ALLOW_UNPROVEN_IDS` — аварийный флаг с безопасным default `false`. При `true` приложение пропускает только статическое доказательство уникальности explicit `_id`, но сохраняет обязательность scalar BSON, запрет missing/null/omit, порядок и `count` межколлекционных ссылок, проверки secondary unique-индексов, target-key probes и окончательное enforcement MongoDB. Прямой `literal`, полностью literal-`concat` и локальная цепочка `ref`, разрешающаяся в literal, отклоняются даже с флагом. Перед первой записью CLI выводит `ID UNIQUENESS PROOF DISABLED`; тот же secret-safe текст сохраняется в generation-report.

## Структура generation-конфига

Файл может быть YAML или JSON. Схема строгая: неизвестные и дублирующиеся свойства отклоняются.

```yaml
version: 1
seed: 12345
templateSelection: SHUFFLED_CYCLE
batchSize: 1000
parallelism: 2
maxWorkingMegabytes: 100
maxInFlightMegabytes: 256

sharedDates:
  operationDate:
    kind: randomRange
    from: 2025-01-01T00:00:00Z
    to: 2026-12-31T23:59:59.999Z

collections:
  - name: customers
    count: 100
    query:
      "profile.templateEnabled": { "$exists": true }
    fields:
      /_id: { kind: objectId }
```

| Свойство | Обязательное | По умолчанию | Назначение |
|---|---:|---:|---|
| `version` | да | — | Только версия `1` |
| `seed` | нет | случайный `long` | Делает результаты воспроизводимыми; фактический seed записывается в отчёт |
| `templateSelection` | нет | `SHUFFLED_CYCLE` | `SHUFFLED_CYCLE` перемешивает каждый цикл шаблонов без повторов; `SEQUENTIAL` сохраняет прежний порядок `i % count` |
| `batchSize` | нет | `1000` | Размер логического блока итераций и логическая граница уникальности строкового `_id`, фиксируемая в отчёте; допустимо `1..100000`. При записи блок может делиться на несколько физических `bulkWrite`-батчей по байтовому лимиту — значения документов при этом не меняются |
| `parallelism` | нет | `2` | Число generation workers, обрабатывающих блоки concurrently; допустимо `1..64` |
| `maxWorkingMegabytes` | нет | `100` | Общий лимит raw-BSON snapshots и индексов; полезны 99%, положительные значения ниже 100 допустимы |
| `maxInFlightMegabytes` | нет | `256` | Байтовый лимит материализованных результатов, ожидающих записи: бюджет поровну делится между workers (без ожидания памяти с уже выделенными данными), физический write-батч ограничен долей воркера и 32 МиБ, а документ больше общего лимита завершает генерацию ошибкой до записи (ловится и validate-only прогоном) |
| `sharedDates` | нет | `{}` | Именованные даты `fixed`/`randomRange`, общие для полей и коллекций одной итерации |
| `collections` | да | — | Непустой упорядоченный список коллекций |

У коллекции обязательны `name`, неотрицательный `count` и объект `fields`. Необязательный объект `query` фильтрует исходные документы до создания snapshot. Необязательный `unconfiguredFields` (`SNAPSHOT` по умолчанию, либо `OMIT`, `DEFAULTS`, `RANDOM`) задаёт обработку полей шаблона, отсутствующих в `fields` (семантика режимов — выше). Имена коллекций не должны повторяться; `system.*` запрещены.

### Фильтрация template snapshot через `query`

Если `query` отсутствует или равен `{}`, snapshot читает все документы коллекции, как и раньше. Иначе объект передаётся MongoDB как обычный `find`-фильтр, после чего совпавшие документы по-прежнему сортируются по `_id` и читаются ограниченными batch-пакетами:

```yaml
collections:
  - name: QWE
    count: 1000000
    query:
      "QWE.ASD":
        "$exists": true
    fields:
      /_id: { kind: randomString, alphabet: ALPHANUMERIC, length: 6 }
```

`$exists: true` выбирает документы, в которых поле присутствует; `$exists: false` выбирает документы без поля. `query` использует MongoDB dot notation, в отличие от ключей `fields` и путей `ref`, которые используют JSON Pointer.

Поддерживаются YAML/JSON-значения: строки, boolean, `null`, целые BSON `int32`/`int64`, decimal, массивы и вложенные объекты. MongoDB Extended JSON (`$oid`, `$date`, `$regularExpression`) специально не преобразуется в BSON-типы. Серверные JavaScript-операторы `$where`, `$function` и `$accumulator` запрещены на любой глубине. Остальной синтаксис фильтра проверяет MongoDB при открытии snapshot cursor.

Фильтр влияет только на шаблоны указанной коллекции: `count` остаётся количеством генерируемых документов, а выбранные шаблоны циклически переиспользуются. Если ни один документ не совпал, стадия `SNAPSHOT_TEMPLATES` завершается до подключения к цели. Содержимое query не попадает в логи или generation-report; `configHash` фиксирует весь файл конфигурации, а snapshot-лог содержит только `queryApplied=true/false`.

В YAML используйте пробелы для отступов: tab-символы синтаксисом YAML не допускаются.

При фиксированном `seed` результат обычного правила зависит от `(seed, collection, iteration, field path)`, а случайная `sharedDate` — только от `(seed, sharedDate name, iteration)`. Выбор шаблона `SHUFFLED_CYCLE` также детерминирован `(seed, collection, cycle)` и вычисляется как bijective-перестановка ordinal с O(1) дополнительной памятью: каждый сохранённый шаблон встречается ровно один раз за цикл. Изменение `parallelism` или порядка выполнения workers не меняет значения. Запись выполняется блоками по `batchSize` последовательных итераций: блоки обрабатываются до `parallelism` воркеров concurrently, порядок завершения блоков недетерминирован, но состав и порядок документов внутри каждого блока фиксированы.

`SHUFFLED_CYCLE` — новый default для `version: 1`. Конфиг без `templateSelection`, ранее рассчитывавший на последовательность шаблонов, должен явно задать `SEQUENTIAL`.

## Пути полей: JSON Pointer

Ключи верхнего уровня в `fields` — это JSON Pointer, а не MongoDB dot notation:

```yaml
fields:
  /name: { kind: literal, value: "Alice" }
  /profile/city: { kind: literal, value: "Tashkent" }
```

- Путь всегда начинается с `/`.
- `~1` означает символ `/` в имени поля, `~0` — символ `~`.
- Нельзя одновременно задавать родительский и дочерний путь, например `/profile` и `/profile/city`.
- Промежуточные документы при необходимости создаются.
- Обращение к массиву по пути возможно только к уже существующему индексу; правило не расширяет исходный массив.
- Чтобы оставить поле шаблона без изменений, просто не добавляйте для него правило.

## Общие параметры любого правила

Любой `kind` поддерживает:

```yaml
/optionalComment:
  kind: randomString
  alphabet: LOWER_LATIN
  length: 20
  nullProbability: 0.05
  omitProbability: 0.10
```

- `nullProbability` — вероятность записать BSON `null`.
- `omitProbability` — вероятность удалить поле из результата.
- Оба значения по умолчанию равны `0`, находятся в диапазоне `0..1`, а их сумма не должна превышать `1`.
- Выбор детерминирован seed-ом.
- Ненулевая вероятность `null` или удаления не позволяет считать правило доказуемо уникальным для unique-индекса.

Сначала проверяется `omitProbability`, затем `nullProbability`, и только потом вычисляется само правило.

## `literal`: статическое значение

`literal` записывает одно и то же значение во все создаваемые документы.

```yaml
fields:
  /status:  { kind: literal, value: "ACTIVE" }
  /attempt: { kind: literal, value: 0 }
  /enabled: { kind: literal, value: true }
  /note:    { kind: literal, value: null }
```

Поддерживаются строки, boolean, целые и дробные числа, `null`, массивы и объекты. Целое YAML-число становится BSON `int32` или `int64` по диапазону, дробное — BSON `decimal128`:

```yaml
/settings:
  kind: literal
  value:
    source: "generated"
    retries: 3
    flags: ["new", "verified"]
```

В Canonical Extended JSON целые BSON-значения отображаются как `{"$numberInt":"3"}` или `{"$numberLong":"2147483648"}`. Это текстовое представление числового BSON-типа, а не вложенный BSON-документ: само значение остаётся числом (`isNumber() == true`, `isDocument() == false`).

Для динамических значений внутри объекта используйте `kind: object`, а не `literal`.

Не используйте статическое значение как `/_id`: такое правило не считается доказуемо уникальным, поэтому проверка unique `_id` отклонит конфиг.

## `randomString`: случайная строка

Фиксированная длина:

```yaml
/code:
  kind: randomString
  alphabet: UPPER_LATIN
  length: 12
```

Диапазон длины:

```yaml
/token:
  kind: randomString
  alphabet: ALPHANUMERIC
  minLength: 16
  maxLength: 32
```

Доступные алфавиты:

| `alphabet` | Символы |
|---|---|
| `UPPER_LATIN` | `A-Z` |
| `LOWER_LATIN` | `a-z` |
| `ALPHANUMERIC` | `A-Z`, `a-z`, `0-9` |
| `HEX` | `0-9`, `a-f` |
| `CUSTOM` | Значение свойства `characters` |

Пример собственного алфавита:

```yaml
/hexLikeCode:
  kind: randomString
  alphabet: CUSTOM
  characters: "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  length: 10
```

При наличии `length` он задаёт фиксированную длину. Иначе обязательны `minLength` и `maxLength`. Пустой `CUSTOM`-алфавит запрещён.

Обычный `randomString` не гарантирует уникальность: короткие строки закономерно могут повторяться и внутри одного batch. Специальное правило действует только для строкового `_id`: приложение резервирует минимальную часть строки под детерминированно перемешанную позицию и гарантирует разные `_id` внутри каждого batch. Между batch повтор допустим и приведёт к замене документа с тем же `_id`.

```yaml
fields:
  /_id:
    kind: randomString
    alphabet: ALPHANUMERIC
    length: 6
  /GFCUS:
    kind: ref
    path: /_id
```

Здесь `_id` и `GFCUS` намеренно равны: `ref` копирует уже созданное значение и не считается второй случайной генерацией. Для batch-уникального строкового `_id` обязательны фиксированный `length`, нулевые `nullProbability`/`omitProbability`, алфавит без повторяющихся символов и пространство не меньше `min(count, batchSize)`. Если эти условия не выполнены, но `randomString` остаётся достижимым и весь `_id` required scalar, конфиг принимается без batch-гарантии и с обязательным предупреждением о коллизиях.

Если `BGPF` — имя коллекции, верхнеуровневое поле задаётся путём `/GFCUS`. Путь `/BGPF/GFCUS` нужен только тогда, когда внутри документа действительно есть вложенный объект `BGPF` с полем `GFCUS`.

## `randomAlphaNumStringBetween`: base-36 строка из числового диапазона

Это правило воспроизводит формат генераторов, которые сначала выбирают целое число, а затем кодируют его строкой:

```yaml
/code:
  kind: randomAlphaNumStringBetween
  min: 10000000
  max: 1000000000
  length: 6
```

Приложение детерминированно выбирает целое из полуинтервала `[min, max)`, переводит его в uppercase base-36 с алфавитом `0-9`, `A-Z` и дополняет нулями слева до точной ширины `length`. При фиксированном seed выбор зависит от обычных координат `(seed, collection, iteration, path)`. Например, число `1` при `length: 6` становится `000001`.

Все три свойства обязательны. `min` и `max` должны быть целыми YAML/JSON-числами и удовлетворять `0 <= min < max`; дробные и строковые значения не принимаются. Размер диапазона `max-min` не может превышать `Long.MAX_VALUE`, `length` должен быть положительным и не больше BSON-лимита, а `max-1` обязан помещаться в указанное количество base-36 символов. `max` не входит в диапазон.

Правило поддерживает общие `nullProbability`/`omitProbability`, может находиться внутри `concat`, `array` и `object`, а созданное им значение можно читать через `ref`. В explicit `_id` оно считается вероятностным источником с точным keyspace `max-min`: приложение показывает collision warning, не включает batch-unique оптимизацию `randomString` и не считает правило доказательством уникальности secondary unique-индекса.

## `randomNumber`: случайное число

```yaml
/age:
  kind: randomNumber
  bsonType: INT32
  min: 18
  max: 90
```

Поддерживаемые BSON-типы:

```yaml
/smallCounter: { kind: randomNumber, bsonType: INT32, min: -100, max: 100 }
/largeCounter: { kind: randomNumber, bsonType: INT64, min: 0, max: 9000000000 }
/ratio:        { kind: randomNumber, bsonType: DOUBLE, min: 0.0, max: 1.0 }
/amount:       { kind: randomNumber, bsonType: DECIMAL128, min: 0.01, max: 9999.99 }
```

Для `INT32` и `INT64` границы должны быть целыми и входят в диапазон выбора. `DOUBLE` и `DECIMAL128` допускают дробные границы. Значения должны помещаться в выбранный BSON-тип.

## `weightedChoice`: выбор по весам

```yaml
/tier:
  kind: weightedChoice
  choices:
    - { value: "GOLD", weight: 10 }
    - { value: "SILVER", weight: 30 }
    - { value: "BRONZE", weight: 60 }
```

Каждый вес должен быть положительным. Сумма не обязана равняться `1` или `100`: используются относительные веса. `value` поддерживает те же статические YAML-типы, что и `literal`, включая объекты и массивы.

```yaml
/delivery:
  kind: weightedChoice
  choices:
    - value: { method: "courier", priority: true }
      weight: 1
    - value: { method: "pickup", priority: false }
      weight: 3
```

Если `value` — объект с полем `kind`, он вычисляется как полноценное вложенное правило, а не записывается как BSON document:

```yaml
/_id:
  kind: weightedChoice
  choices:
    - { value: { kind: randomAlphaNumStringBetween, min: 10000000, max: 1000000000, length: 6 }, weight: 500 }
    - { value: { kind: randomAlphaNumStringBetween, min: 0, max: 392000, length: 6 }, weight: 490 }
    - { value: { kind: randomAlphaNumStringBetween, min: 1000000, max: 1001800, length: 6 }, weight: 9 }
    - { value: { kind: randomAlphaNumStringBetween, min: 2000000, max: 2000002, length: 6 }, weight: 1 }
```

Вложенным может быть любое правило, включая `concat`, `ref`, `array` и `object`. Сначала применяются `nullProbability`/`omitProbability` самого `weightedChoice`, затем независимо применяются options выбранного правила. Все возможные `ref` участвуют в проверке зависимостей и порядке вычисления полей. `AUTO_AFTER_TARGET_MAX` внутри choice запрещён; explicit `sequence` разрешён.

Обычный scalar, array или object без `kind` остаётся сокращённой записью `literal`. Если literal-объект сам должен содержать поле `kind`, оберните его явно:

```yaml
- value:
    kind: literal
    value: { kind: domain-value, enabled: true }
  weight: 1
```

Для explicit `/_id` каждая ветка должна быть required и возвращать scalar BSON. Без `allow-unproven-ids` случайный источник `randomString` или `randomAlphaNumStringBetween` должен быть достижим в каждой возможной ветке. Из-за весов, пересекающихся диапазонов и произвольной вложенности collision warning для такого `_id` консервативно показывает `keyspace=unknown` и `risk=unknown`; batch-unique оптимизация к `weightedChoice` не применяется.

## `randomBoolean`: случайный boolean

```yaml
/active:
  kind: randomBoolean
  trueProbability: 0.8
```

`trueProbability` находится в диапазоне `0..1` и по умолчанию равен `0.5`.

## `sequence`: числовая последовательность

Явный старт:

```yaml
/ordinal:
  kind: sequence
  start: 1000
  step: 5
```

Для итераций `0`, `1`, `2` получатся BSON `int64` значения `1000`, `1005`, `1010`. `step` по умолчанию равен `1` и не может быть нулём; при явном старте допустим отрицательный шаг.

Старт после максимума в целевой коллекции:

```yaml
/sequence:
  kind: sequence
  start: AUTO_AFTER_TARGET_MAX
  step: 1
```

В этом режиме приложение находит максимальное целочисленное значение поля в цели и начинает с `max + step`; если значений нет, старт равен `1`. Для `AUTO_AFTER_TARGET_MAX` шаг должен быть положительным. Такое правило должно быть назначено полю документа: оно не поддерживается внутри `concat` или `array`, но может находиться в поле `object`.

Переполнение BSON `int64` проверяется до записи.

## `objectId`: детерминированный ObjectId

```yaml
/_id:
  kind: objectId
```

Создаёт BSON ObjectId. Значение детерминировано seed-ом, коллекцией, итерацией и путём. Это рекомендуемый простой вариант для нового MongoDB `_id` и считается доказуемо уникальным внутри запуска.

## `uuid`: детерминированный UUID

BSON binary UUID — вариант по умолчанию:

```yaml
/_id:
  kind: uuid
```

Явная форма:

```yaml
/binaryUuid: { kind: uuid, output: BSON_BINARY }
/textUuid:   { kind: uuid, output: STRING }
```

`BSON_BINARY` сохраняет UUID как BSON binary subtype UUID, `STRING` — как каноническую строку вида `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Оба варианта детерминированы и могут доказывать уникальность.

## `dateTime`: дата или форматированная строка

У `dateTime` обязательны `source` и `output`. Источник имеет один из четырёх видов.

### Случайная дата в диапазоне

```yaml
/createdAt:
  kind: dateTime
  source:
    kind: randomRange
    from: 2020-01-01T00:00:00Z
    to: 2030-01-01T00:00:00Z
  output: BSON_DATE
```

Границы должны быть ISO-8601 instant и задаются с точностью до миллисекунд BSON Date.

### Фиксированная дата

```yaml
/expiresAt:
  kind: dateTime
  source:
    kind: fixed
    value: 2030-12-31T23:59:59Z
  output: BSON_DATE
```

### Именованная общая дата

Если один момент времени нужен в нескольких полях или коллекциях, объявите его один раз в корневой секции `sharedDates`. Поддерживаются `fixed` и `randomRange`:

```yaml
sharedDates:
  operationDate:
    kind: randomRange
    from: 2025-01-01T00:00:00Z
    to: 2026-12-31T23:59:59.999Z
```

Затем каждое поле независимо выбирает BSON-тип или строковый формат:

```yaml
/operationDate:
  kind: dateTime
  source: { kind: shared, name: operationDate }
  output: BSON_DATE

/operationText:
  kind: dateTime
  source: { kind: shared, name: operationDate }
  output: STRING
  pattern: "uuuu-MM-dd'T'HH:mm:ss.SSSX"
  zone: UTC

/legacyDate:
  kind: dateTime
  source: { kind: shared, name: operationDate }
  output: STRING
  pattern: "'1'yyMMdd"
  zone: UTC
```

Для документов с одинаковым номером итерации `i` все ссылки на `operationDate` получают один `Instant`, независимо от коллекции, пути поля, `batchSize`, `parallelism` и порядка выполнения. Ограничения порядка и `count` обычных межколлекционных `ref` здесь не применяются. `fixed` по своей природе одинаков для всех итераций; `randomRange` воспроизводимо выбирает значение отдельно для каждой итерации, включая обе заданные границы.

Общая дата нормализуется до точности BSON Date — миллисекунд. Поэтому BSON и строковое представление описывают ровно один момент времени. `zone` влияет только на отображение: один `Instant` в разных timezone может попасть на разные календарные даты.

Pattern использует Java `DateTimeFormatter`. Для календарного года применяйте `uuuu` или `yy`, а не week-based `YYYY`; литеральный префикс заключайте в одинарные кавычки. Например, `"'1'yyMMdd"` преобразует 2 сентября 2026 года в `1260902`.

### Дата из другого поля

```yaml
/createdText:
  kind: dateTime
  source:
    kind: ref
    path: /createdAt
  output: STRING
  pattern: "uuuu-MM-dd'T'HH:mm:ss.SSSXXX"
  zone: UTC
  locale: ROOT
```

Ссылка может быть локальной или межколлекционной:

```yaml
source:
  kind: ref
  collection: customers
  path: /createdAt
```

Ссылаемое значение должно быть BSON Date или строкой ISO-8601 instant. Для `output: BSON_DATE` результат остаётся датой MongoDB. Для `output: STRING` обязателен Java `DateTimeFormatter`-pattern; `zone` по умолчанию `UTC`, `locale` — `ROOT`.

Для года рекомендуется `uuuu`, а текстовые части pattern заключаются в одинарные кавычки, например `"uuuu-MM-dd'T'HH:mm:ssXXX"`.

## `ref`: ссылка на значение

Локальная ссылка читает уже существующее или сгенерированное поле текущего документа:

```yaml
/sequence: { kind: sequence, start: 1 }
/copyOfSequence:
  kind: ref
  path: /sequence
```

Поля автоматически вычисляются в порядке зависимостей, поэтому физический порядок `/sequence` и `/copyOfSequence` в YAML не обязан совпадать. Циклические ссылки отклоняются.

Межколлекционная ссылка:

```yaml
collections:
  - name: customers
    count: 100
    fields:
      /_id: { kind: objectId }

  - name: orders
    count: 100
    fields:
      /_id: { kind: objectId }
      /customerId:
        kind: ref
        collection: customers
        path: /_id
```

`orders[i].customerId` получит `_id` из `customers[i]`. Коллекция-источник ссылки должна находиться выше в списке, а её `count` должен быть не меньше `count` зависимой коллекции.

Поведение при отсутствии пути:

```yaml
/copiedValue:
  kind: ref
  path: /possiblyMissing
  onMissing: NULL
```

| `onMissing` | Результат |
|---|---|
| `ERROR` | Ошибка генерации; значение по умолчанию |
| `NULL` | BSON `null` |
| `OMIT` | Целевое поле удаляется |

`ref` копирует исходный BSON-тип без преобразования.

## `concat`: строка из нескольких частей

```yaml
/sequence: { kind: sequence, start: 100 }
/_id:
  kind: concat
  parts:
    - { kind: literal, value: "ORD-" }
    - { kind: ref, path: /sequence }
```

Результатом всегда является строка, например `ORD-100`. Частями могут быть вложенные правила. Поддерживаются строковые, boolean, числовые и ObjectId-значения. Для даты сначала используйте `dateTime` с `output: STRING`; UUID для concat также задавайте с `output: STRING`.

Если часть вернула `null`, в строку добавится текст `null`. Если часть вернула `OMIT`, всё поле `concat` будет удалено.

Для unique-индекса доказуемым считается concat из статических `literal`-частей и ровно одного доказуемо уникального компонента.

Строковый `_id` может объединять batch-уникальное generated-поле и scalar-поля одного выбранного raw-BSON шаблона:

```yaml
/generatedIdPart:
  kind: randomString
  alphabet: ALPHANUMERIC
  length: 6
/_id:
  kind: concat
  parts:
    - { kind: ref, path: /generatedIdPart }
    - { kind: literal, value: "|" }
    - { kind: ref, path: /templateCode }
    - { kind: literal, value: "|" }
    - { kind: ref, path: /region }
```

Если первым непостоянным компонентом является fixed-length `randomString` либо локальный `ref`, разрешающийся в него, и перед ним находятся только `literal`, этот источник получает прежнюю batch-уникальную суффиксную позицию. Это оптимизация с проверкой вместимости `alphabetSize ^ length`, а не условие допуска всего `_id`.

Если прежнее доказательство уникальности не сработало, достаточно хотя бы одного достижимого `randomString` или `randomAlphaNumStringBetween`: напрямую, во вложенном `concat`, через цепочку локальных `ref` или через `ref` на поле ранее объявленной коллекции. Остальные required scalar-компоненты могут находиться в любом порядке и быть literals, template refs или ref-to-literal. Повторные ссылки на один путь считаются одним random-источником; независимые источники перемножают keyspace. Fixed-length `randomString` даёт `alphabetSize^length`, а `randomAlphaNumStringBetween` — `max-min`; variable-length или неразрешимый источник даёт `risk=unknown`. Для полного `count` выводится birthday-оценка, а `count > keyspace` отмечается как гарантированная коллизия. Это warning, а не блокирующий порог.

Каждый сохранённый шаблон проверяется coverage до первой пробы записи. Missing, BSON `null`, object или array в template-компоненте составного `_id` завершает preflight. Неизменяемое template-поле не обязано иметь собственное правило в `fields`. Разделители и escaping не добавляются автоматически — используйте явные `literal`. Coverage вычисляет значения теми же координатами, что и фаза записи, включая путь batch-уникального строкового `_id` и ёмкость блока `min(count, batchSize)` — validate-only проверяет именно тот путь вывода batch-уникальных суффиксов, который будет использоваться при записи.

## `array`: массив значений

Фиксированная длина:

```yaml
/tags:
  kind: array
  length: 3
  items:
    kind: randomString
    alphabet: LOWER_LATIN
    length: 8
```

Случайная длина:

```yaml
/roles:
  kind: array
  length: { min: 1, max: 4 }
  items:
    kind: weightedChoice
    choices:
      - { value: "reader", weight: 5 }
      - { value: "writer", weight: 1 }
```

Длина находится в диапазоне `0..1000000`. Для каждого элемента `items` вычисляется отдельно. Если вложенное правило возвращает `OMIT`, элемент не добавляется, поэтому фактическая длина может быть меньше выбранной.

`AUTO_AFTER_TARGET_MAX` внутри массива не поддерживается.

## `object`: объект с динамическими полями

```yaml
/profile:
  kind: object
  fields:
    label:
      kind: literal
      value: "generated"
    code:
      kind: randomString
      alphabet: HEX
      length: 12
    enabled:
      kind: randomBoolean
      trueProbability: 0.9
```

Имена внутри `object.fields` — обычные имена полей объекта, не JSON Pointer. Каждое значение является полноценным правилом и может быть `literal`, `ref`, вложенным `object`, `array` и так далее.

`object` заменяет всё значение верхнего поля. Если нужно изменить только одно поле существующего объекта шаблона и сохранить остальные, используйте верхнеуровневый путь:

```yaml
fields:
  /profile/status: { kind: literal, value: "ACTIVE" }
```

## Как выбирается `_id`

Для предсказуемого результата рекомендуется всегда задавать `/_id` явно через `objectId`, `uuid`, `sequence` или доказуемо уникальный `concat`. Составной строковый `_id` с `randomString` или `randomAlphaNumStringBetween` остаётся стратегией `EXPLICIT` и работает через exact-id upsert: совпадение с целью или внутри запуска полностью заменяет документ. Наличие случайного источника разрешает недоказанный `_id`, но не объявляется абсолютной уникальностью.

Если правила `/_id` нет, приложение анализирует все шаблоны коллекции:

1. Если исходный `_id` во всех сохранённых шаблонах равен одному и тому же единственному scalar-полю, новый `_id` берётся из этого поля при условии доказуемой уникальности его правила.
2. Однородный ObjectId заменяется детерминированным ObjectId.
3. Однородный UUID заменяется детерминированным UUID.
4. Однородные `int32`/`int64` получают последовательность выше максимального `_id` в цели.
5. Строковый, смешанный, объектный, массивный, отсутствующий или неоднозначный `_id` требует явного `/_id`.

Автоматический concat не строится.

## Unique-индексы

Перед записью проверяются `_id_` и все unique-индексы цели.

- Partial, sparse и unique-индексы с non-simple collation в generation v1 не поддерживаются.
- Хотя бы один компонент обычного unique-индекса должен быть доказуемо уникальным: `sequence`, `objectId`, `uuid`, строковый `_id`, one-to-one `ref` на доказуемо уникальное поле или допустимый `concat`.
- Обычные `literal`, `randomString`, `randomAlphaNumStringBetween`, `randomNumber`, `weightedChoice` и `randomBoolean` сами по себе уникальность не доказывают.
- Random fallback и `allow-unproven-ids` применяются только к автоматическому `_id_`; secondary unique-индексы продолжают требовать статическое доказательство.
- `_id_` допускает совпадение и обрабатывается upsert-ом. Перед записью secondary unique-ключ проверяется в цели с исключением документа с тем же `_id`.
- Конкурентная запись всё равно возможна; окончательным арбитром остаётся MongoDB.

## Проверка и типичные ошибки

Используйте validate-only перед большим запуском:

```bash
java -jar build/libs/DataPorterGen.jar \
  --migration.mode=GENERATE \
  --migration.source.uri='mongodb://host:27017' \
  --migration.source.database=catalog \
  --migration.target.uri='mongodb://host:27017' \
  --migration.target.database=catalog \
  --migration.generation.config-path=./generation.yml \
  --migration.generation.validate-only=true \
  --migration.generation.allow-unproven-ids=false
```

Частые причины ошибок:

- исходная коллекция отсутствует, является view или не содержит шаблонов;
- целевая коллекция ещё не создана;
- explicit `/_id` допускает null/omit, может вернуть missing, BSON null, object или array;
- недоказанный explicit `_id` не содержит достижимого `randomString` или `randomAlphaNumStringBetween`, а `allow-unproven-ids` выключен;
- межколлекционная ссылка направлена вниз по списку или родительская коллекция имеет меньший `count`;
- `dateTime.source.shared` ссылается на неизвестную `sharedDate` или её определение содержит неподдерживаемый источник;
- одновременно заданы конфликтующие пути вроде `/profile` и `/profile/name`;
- `dateTime` содержит неверный instant, pattern, timezone или locale;
- целевой unique-индекс не поддерживается или его уникальность нельзя доказать;
- сгенерированный документ превышает BSON-лимит MongoDB 16 MiB.

Validate-only не моделирует будущие конкурентные записи и не может полностью предсказать произвольные MongoDB validator expressions.

## Временные snapshots и отчёт

Raw-BSON snapshots могут содержать чувствительные документы. Они создаются в owner-only каталогах `dataporter-generation-*` во временном каталоге JVM и удаляются при штатном завершении, обработанной ошибке или отмене. После аварийного завершения сначала найдите устаревшие каталоги своего пользователя:

```bash
find /tmp -maxdepth 1 -type d -user "$(id -un)" -name 'dataporter-generation-*' -mtime +1 -print
```

Удаляйте только проверенные каталоги, которые не используются работающим процессом.

Generation-report содержит фактический seed, `templateSelection`, `validateOnly`, `allowUnprovenIds`, SHA-256 конфига, counts, разрешённые `_id`-стратегии, `snapshotTemplates`, `snapshotBytes`, `snapshotTruncated`, объём сгенерированного BSON, длительности стадий, warnings и `safeToRetry`. Поле `written` считает успешные операции вставки/замены, а не прирост размера коллекции. Он не содержит исходные или сгенерированные документы, значения полей, credentials либо чувствительные URI query parameters.

Полный рабочий пример находится в [generation.example.yml](generation.example.yml). Docker Compose-сценарий использует [docker/generation/generation.yml](docker/generation/generation.yml).
