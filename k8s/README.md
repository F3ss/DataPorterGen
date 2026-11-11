# Запуск DataPorterGen в Kubernetes

[← Основной README](../README.md) | [Руководство по генерации](../GENERATION.md)

В данном руководстве описан запуск DataPorterGen в кластере Kubernetes без использования Helm (чистые манифесты `kubectl`).

Команды ниже выполняются из корня репозитория в Bash. Нужны Docker для сборки образа, `kubectl` с доступом к кластеру и доступные из подов MongoDB. Все ресурсы создаются в текущем namespace; при необходимости добавляйте один и тот же `-n <namespace>` ко всем командам `kubectl`.

---

## Содержание

1. [Архитектура: Job vs Deployment](#архитектура-job-vs-deployment)
2. [Структура манифестов](#структура-манифестов)
3. [Подготовка и сборка образа](#подготовка-и-сборка-образа)
4. [Настройка конфигурации](#настройка-конфигурации)
5. [Пошаговый запуск](#пошаговый-запуск)
   - [Вариант 1 (Рекомендуемый): Kubernetes Job](#вариант-1-рекомендуемый-kubernetes-job)
   - [Вариант 2: Kubernetes Deployment](#вариант-2-kubernetes-deployment)
6. [Несколько независимых реплик](#несколько-независимых-реплик)
7. [Мониторинг, логи и скачивание отчетов](#мониторинг-логи-и-скачивание-отчетов)
8. [Очистка ресурсов](#очистка-ресурсов)

---

## Архитектура: Job vs Deployment

DataPorterGen — это CLI-приложение пакетной обработки: оно стартует, считывает конфигурацию, подключается к MongoDB, выполняет миграцию или генерацию данных и завершает процесс. Код `0` означает успех; ненулевой код — ошибку или прерывание (подробнее в [основном README](../README.md#коды-завершения-и-отчёт)).

| Параметр | Kubernetes Job (`batch/v1`) | Kubernetes Deployment (`apps/v1`) |
|---|---|---|
| **Назначение** | Пакетные разовые задачи (*run-to-completion*) | Долгоживущие сервисы (веб-серверы, демоны) |
| **`restartPolicy`** | `Never` или `OnFailure` | Только `Always` (требование k8s API) |
| **Поведение при завершении процесса** | При успехе под получает фазу `Succeeded` (`Completed` в выводе `kubectl`); при ошибке — `Failed`. Job может создать новый под в пределах `backoffLimit`. | Без обёртки ожидания kubelet перезапускает контейнер даже после кода `0`, повторяя операцию. |
| **Параллельные запуски** | `parallelism` ограничивает число одновременно работающих подов, `completions` задаёт число успешных завершений. | `replicas` задаёт желаемое число подов. |
| **Рекомендация** | **Рекомендуемый выбор для DataPorterGen.** | Требует удержания процесса (например, `sleep infinity`) после успешного завершения. |

В каталоге `k8s/` представлены оба варианта.

---

## Структура манифестов

Каталог `k8s/` содержит:

- **`configmap.yaml`**: Переменные окружения приложения и конфигурационный файл `generation.yml`, монтируемый в контейнер по пути `/app/config/generation.yml`.
- **`secret.yaml`**: Пример Secret со строками подключения к MongoDB (`SOURCE_MONGODB_URI`, `TARGET_MONGODB_URI`). `stringData` содержит открытый текст; Secret сам по себе не маскирует и не шифрует пароли.
- **`job.yaml`**: Спецификация Kubernetes Job с параллельными репликами и политикой `restartPolicy: Never`.
- **`deployment.yaml`**: Спецификация Deployment со скриптом ожидания (`sleep infinity`) после успешного запуска. Пересоздание пода или перезапуск контейнера снова запускает приложение.

---

## Подготовка и сборка образа

Соберите Docker-образ приложения и опубликуйте его в ваш Docker Registry (или загрузите в кластер minikube/kind):

```bash
# Сборка образа
docker build -t dataportergen:latest .

# При использовании локального kind:
# kind load docker-image dataportergen:latest

# При использовании minikube:
# minikube image load dataportergen:latest

# При использовании удалённого реестра (замените registry.example.com на свой):
# docker tag dataportergen:latest registry.example.com/dataportergen:latest
# docker push registry.example.com/dataportergen:latest
```

> **Примечание:** Если вы используете удаленный реестр, укажите соответствующий путь к образу в полях `image:` файлов `k8s/job.yaml` или `k8s/deployment.yaml`.

В манифестах задано `imagePullPolicy: IfNotPresent`. Для обновлений используйте новый тег или digest: повторная сборка `latest` не обновляет уже закешированный на узле образ. Для закрытого реестра настройте `imagePullSecrets`.

---

## Настройка конфигурации

### 1. Реквизиты подключения (`k8s/secret.yaml`)

Укажите URI источника и цели в `k8s/secret.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: dataportergen-secret
type: Opaque
stringData:
  SOURCE_MONGODB_URI: "mongodb://user:password@mongo-source:27017/?authSource=admin"
  TARGET_MONGODB_URI: "mongodb://user:password@mongo-target:27017/?authSource=admin"
```

Не сохраняйте реальные реквизиты в Git; используйте локальный файл Secret вне репозитория или принятый в кластере менеджер секретов. Ограничьте доступ через RBAC и настройте шифрование Secrets при хранении согласно [документации Kubernetes](https://kubernetes.io/docs/concepts/configuration/secret/). Имена хостов в примере — заглушки: эти манифесты не разворачивают MongoDB и не создают для неё Service. Имена сервисов из Docker Compose автоматически в Kubernetes не доступны.

### 2. Параметры генерации и окружение (`k8s/configmap.yaml`)

В файле `k8s/configmap.yaml`:

- **Переменные окружения**:
  - `MIGRATION_MODE`: режим работы (`GENERATE` для генерации или `MIGRATE` для копирования).
  - `SOURCE_DATABASE` / `TARGET_DATABASE`: имена баз данных.
  - `GENERATION_CONFIG_PATH`: путь к смонтированному файлу `/app/config/generation.yml`.
  - `GENERATION_VALIDATE_ONLY`: `true` для проверки без записи; режим всё равно читает источник и цель, но не проверяет возможность записи. По умолчанию в примере `false`.
  - `GENERATION_ALLOW_UNPROVEN_IDS`: при `true` отключает только статическое доказательство уникальности explicit `_id`; остальные проверки сохраняются. Оставляйте `false`, если обход не требуется осознанно.
  - `MIGRATION_REPORT_PATH`: путь JSON-отчёта, в примере `/app/reports/generation-report.json`.
- **Секция `generation.yml`**: Полные правила генерации документов (коллекции, объемы `count`, правила для полей `fields`).

Пример настроен на `GENERATE`: для `customers` выполняется `5000` upsert-операций **в каждом запуске**, а не суммарно на все поды. Исходная коллекция должна содержать документы-шаблоны, а целевая коллекция должна уже существовать и иметь совместимые метаданные. Генерация не создаёт коллекции, индексы и views. Проверьте также ограничения secondary unique-индексов: неуказанные поля по умолчанию копируются из шаблона и могут конфликтовать. Полная схема описана в [руководстве по генерации](../GENERATION.md).

Для `MIGRATE` задайте `MIGRATION_MODE: "MIGRATE"`, нужные базы, `TARGET_STRATEGY` (`FAIL_IF_EXISTS` по умолчанию, `DROP_AND_RECREATE` или `MERGE`) и отдельный путь отчёта, например `/app/reports/migration-report.json`. Файл генерации и `GENERATION_VALIDATE_ONLY` не управляют миграцией: этот флаг не является dry-run для `MIGRATE`. Запускайте одну миграцию на целевую базу (`parallelism: 1`, `completions: 1` у Job); параллельные поды не делят работу. Только `DROP_AND_RECREATE` удаляет целевую базу целиком; `MERGE` заменяет документы с совпадающим `_id` документами источника. Фильтры `INCLUDE_COLLECTIONS` и `EXCLUDE_COLLECTIONS` взаимно исключаются. Подробности — в [основном README](../README.md).

---

## Пошаговый запуск

Перед первым запуском `GENERATE` рекомендуется установить `GENERATION_VALIDATE_ONLY: "true"` и выполнить проверку одним подом. Для Job задайте `parallelism: 1`, `completions: 1`, а для Deployment — `replicas: 1`. У Job также рекомендуется `backoffLimit: 0`, чтобы после ошибки записи сначала проверить цель вручную. Текущие примеры содержат по три параллельных запуска, а Job — `backoffLimit: 2`; особенности описаны ниже.

Примените Secret и ConfigMap, затем **один** выбранный вариант нагрузки. Если Secret хранится в локальном файле, замените путь в команде:

```bash
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/configmap.yaml
```

### Вариант 1 (Рекомендуемый): Kubernetes Job

Без изменений `k8s/job.yaml` запрашивает три успешных запуска (`parallelism: 3`, `completions: 3`). Это повторение всей операции в каждом поде; прежде чем запускать пример, прочитайте [раздел о репликах](#несколько-независимых-реплик).

```bash
# Запуск Job
kubectl apply -f k8s/job.yaml

# Просмотр статуса Job
kubectl get jobs -l app=dataportergen

# Просмотр состояния подов
kubectl get pods -l app=dataportergen

# Ожидание успешного завершения (при необходимости увеличьте таймаут)
kubectl wait --for=condition=complete --timeout=30m job/dataportergen-job
```

`kubectl wait` выше ждёт успеха; при переходе Job в `Failed` команда может ждать до таймаута. Причину проверяйте через `kubectl describe job dataportergen-job`, состояние подов и логи.

Для исходного `completions: 3` после успешного выполнения вывод будет следующим (время приведено для примера):

```text
NAME                COMPLETIONS   DURATION   AGE
dataportergen-job   3/3           25s        30s
```
Успешно завершившиеся поды получат статус `Completed`. При `completions: 1` ожидается `1/1`.

`restartPolicy: Never` запрещает перезапуск контейнера внутри того же пода, но Job с `backoffLimit: 2` может создавать новые поды после ошибок. Это повторяет всю операцию, а не продолжает её. Даже при `backoffLimit: 0` Job не гарантирует ровно один запуск — это ограничение [контроллера Job](https://kubernetes.io/docs/concepts/workloads/controllers/job/). После неоднозначной записи сначала проверьте цель и `safeToRetry` в отчёте.

Повторный `kubectl apply` завершённого Job не запускает его заново. Для нового запуска после проверки результата удалите старый Job и создайте его снова:

```bash
kubectl delete -f k8s/job.yaml
kubectl apply -f k8s/job.yaml
```

Удаление также удаляет поды и их `emptyDir`; сначала сохраните необходимые логи и отчёты. При переходе от validate-only к записи установите `GENERATION_VALIDATE_ONLY: "false"`, снова примените ConfigMap и создайте новый Job. Изменение ConfigMap или Secret не меняет окружение уже созданных контейнеров, а файл ConfigMap, смонтированный через `subPath`, автоматически не обновляется.

### Вариант 2: Kubernetes Deployment

Если по регламенту инфраструктуры необходим именно Deployment:

```bash
# Запуск Deployment
kubectl apply -f k8s/deployment.yaml

# Просмотр состояния реплик
kubectl get deployment dataportergen
kubectl get pods -l app=dataportergen
```

В данном манифесте контейнер после кода `0` переходит в режим ожидания (`sleep infinity`). Под остаётся в состоянии `Running`; резервирование ресурсов сохраняется. Статус `Running`/`Ready` и успешный rollout не доказывают завершение генерации — проверяйте логи и отчёт. При ненулевом коде контейнер завершается и Kubernetes перезапускает его.

Ожидание предотвращает немедленный повтор после успеха, но сбой узла, перезапуск контейнера, rollout или пересоздание пода снова запустят операцию. Для миграции, особенно с `DROP_AND_RECREATE`, используйте Job. Изменения ConfigMap/Secret требуют новых подов; в Deployment это можно сделать через `kubectl rollout restart deployment/dataportergen`, учитывая повторное выполнение операции.

---

## Несколько независимых реплик

Когда `parallelism` (в Job) или `replicas` (в Deployment) больше 1, каждый под независимо читает шаблоны, проверяет цель и выполняет полный `count`. Между подами нет распределения итераций или координации; они могут конфликтовать на `_id` и других уникальных ключах. Внутренняя настройка `generation.yml.parallelism: 2` управляет workers одного процесса: три пода дают до шести workers.

В текущем ConfigMap задан `seed: 12345`. Все случайные правила, включая `objectId`, `randomString` и `randomAlphaNumStringBetween`, детерминированы по seed. При одинаковых шаблонах и конфигурации поды получают одинаковые ID и заменяют одни и те же документы через upsert. Три успешных запуска по `5000` операций не означают прирост на `15000` документов. Если источник совпадает с целью, snapshots разных запусков могут отличаться: общего snapshot между подами нет.

Для независимых наборов со случайными ID можно убрать `seed` из `generation.yml`: каждый процесс выберет случайный seed и запишет его в отчёт. Это снижает риск совпадений, но не гарантирует уникальность между подами или относительно существующей цели. Изменение seed не разделяет диапазоны явных `sequence`, а вычисление `max+1` несколькими процессами подвержено гонкам. Для гарантированного разделения задавайте отдельные конфигурации с непересекающимися пространствами ID и secondary unique-ключей либо разные целевые базы. Статическая проверка одного процесса не координирует записи остальных.

---

## Мониторинг, логи и скачивание отчетов

### Просмотр логов генерации

```bash
# Логи подов Job потоком (для исходного parallelism: 3):
kubectl logs -l batch.kubernetes.io/job-name=dataportergen-job -c dataportergen --prefix=true --max-log-requests=3 --tail=-1 -f

# Логи конкретного пода (замените pod-name на его имя):
kubectl logs pod-name
```

Для Deployment используйте селектор `app=dataportergen`; он также выберет поды Job, если запущены оба варианта. При большем числе подов увеличьте `--max-log-requests`. После перезапуска контейнера предыдущие логи можно запросить через `kubectl logs <pod-name> --previous`.

### Скачивание JSON-отчета с результатами

В текущей конфигурации приложение записывает JSON-отчёт в `/app/reports/generation-report.json`; для другого режима/пути используйте значение `MIGRATION_REPORT_PATH`. При ранней ошибке конфигурации или аварийном завершении отчёт может отсутствовать.

Том `emptyDir` отдельный для каждого пода. Он переживает перезапуск контейнера внутри пода, но удаляется вместе с подом. **Из завершённого контейнера Job нельзя скачать файл через `kubectl cp`**: команда запускает `tar` внутри работающего контейнера. Наличие `emptyDir` этого не меняет. Для отчётов Job заранее настройте постоянное хранилище (PVC с отдельным подом для чтения) или выгрузку во внешнее хранилище до выхода контейнера.

Для варианта Deployment файл можно скопировать после успешного завершения Java, пока контейнер продолжает `sleep infinity`. Сначала выберите нужный под и убедитесь по его логам, что операция завершена:

```bash
# Выбрать конкретный работающий под Deployment из списка:
kubectl get pods -l app=dataportergen
POD_NAME='pod-name' # замените на имя выбранного пода

# Скопировать отчет на локальную машину:
kubectl cp -c dataportergen "${POD_NAME}:/app/reports/generation-report.json" "./${POD_NAME}-generation-report.json"

# Проверить статус выполнения в отчете:
cat "./${POD_NAME}-generation-report.json"
```

Проверьте верхнеуровневые `status`, `validateOnly`, `safeToRetry` и счётчики `collections[].requested`, `generated`, `written`; поля `generatedDocuments` в отчёте нет. Сохраните отдельный отчёт каждого пода. После ошибки и перезапуска в томе может оставаться отчёт предыдущей попытки — сверяйте `generationId` и временные метки. Требования команды приведены в [документации `kubectl cp`](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_cp/).

---

## Очистка ресурсов

Сначала сохраните нужные результаты. Удаление Job/Deployment удаляет их поды и тома `emptyDir`. Команды ниже удаляют ресурсы приложения в Kubernetes; коллекции MongoDB остаются.

```bash
# Удаление Job и завершенных подов:
kubectl delete -f k8s/job.yaml

# Удаление Deployment:
kubectl delete -f k8s/deployment.yaml

# Удаление конфигураций и секретов:
kubectl delete -f k8s/configmap.yaml
kubectl delete -f k8s/secret.yaml
```
