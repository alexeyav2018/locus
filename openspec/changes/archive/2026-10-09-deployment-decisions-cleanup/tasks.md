## 1. Журнал, документы, комментарии

- [x] 1.1 Написать `openspec/context/adr/0051-ekspluataciya-stenda-vne-repozitoriya.md` (`Предложено`, область «техника») по design.md Р1–Р2; пересобрать индекс `build_index.py`, `--check` зелёный
- [x] 1.2 `architecture.md`: «Развёртывание» — держатель со ссылкой на ADR-0051; «Локальный запуск» — переписать фразу «единственное, что до `deployment-backup` вообще проверяет апгрейд-путь»
- [x] 1.3 Комментарии `compose.yaml:8`, `application.yaml:66`, `FileStorageProperties.java:14` — «из окружения стенда (ADR-0051)» без отменённого элемента
- [x] 1.4 `openspec/backlog.md`: пометка об отмене `deployment-backup` — ссылка на ADR-0051; `CLAUDE.md` — строка ADR-0051 в сводке ключевых решений
- [x] 1.5 Признак готовности: `grep -rn deployment-backup openspec/context CLAUDE.md src compose.yaml` — только тексты неизменяемых записей журнала (0020, 0023, 0024 — объяснены ADR-0051), пометки об отмене (0043, 0046, 0051, `architecture.md`); `openspec validate deployment-decisions-cleanup` и `mvn clean package` зелёные

## Workflow follow-up

- При архивации: ADR-0051 → `Принято` (показана владельцу на гейте), индекс пересобрать; карточка `deployment-decisions-cleanup` — ✅ со ссылкой на архив.
