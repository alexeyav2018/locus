# Tasks

Документы, которые изменение затрагивает: `openspec/context/glossary.md`,
`domain-model.md`, `scenarios.md`, `openspec/backlog.md`, `CLAUDE.md`
и запись `adr/0048`. Не затрагивает: `strategy.md` (редакция правится
только целиком; достижение горизонта проверяется при архивации),
`architecture.md`, `standards.md`, `antipatterns.md`, `ux-concept.md`
(новая страница следует его правилам, не меняя их).

## 1. Схема, запись, репозиторий

- [x] 1.1 Миграция `db/changelog/migrations/0013-schedule-changes.yaml`:
      уникальный ключ `uq_lesson_id_user` на `lesson(id, user_id)`;
      таблица `meeting_adjustment` по design.md (уникальность
      `(lesson_id, planned_date)`, FK `user_id → user_`, составной FK
      `(lesson_id, user_id) → lesson(id, user_id)` `on delete cascade`,
      проверки сырым SQL). Мастер подключает папку целиком (`includeAll`),
      правка `db.changelog-master.yaml` не нужна.
      Проверить: `MigrationsOnStartupTest`
- [x] 1.2 `MeetingAdjustment` с `Move` (проверки конструктора повторяют
      проверки базы, `isEmpty()`), `LessonTiming.occursOn(date)`.
      Проверить модульными тестами без базы
- [x] 1.3 `MeetingAdjustmentRepository` на `JdbcClient` с `UserId` в каждом
      публичном методе: `find(owner, lesson, plannedDate)`,
      `findForLessons(owner, lessons, from, to)` (плановая или новая дата
      в отрезке), `save` (upsert), `delete`, `findByLesson`,
      `rehome(owner, from, to, sinceDate)`, `deleteDates`. Проверить
      `MeetingAdjustmentRepositoryTest` на двух владельцах: чужое Занятие
      база не принимает, каскад с Занятием и с Группой, отбор на краях
      отрезка
- [x] 1.4 `OwnerIsRequiredByLessonsTest` распространить
      на `MeetingAdjustmentRepository` и таблицу `meeting_adjustment`
      (или отдельный тест по тому же образцу). Проверить: тест зелёный

## 2. Встречи с поправками

- [x] 2.1 `Meeting` получает плановую дату, отмену, неявку и признак
      переноса; `Meetings.between(lessons, adjustments, from, to)` по
      design.md, правило дат — через `occursOn`. Проверить `MeetingsTest`:
      отмена, перенос внутри недели, перенос в другую неделю (в обе
      стороны), повторный перенос, неявка, Поправка на дату, которой
      правило не даёт, не показывается
- [x] 2.2 `LessonRepository.findCandidates` отбирает и Занятия с переносом
      в отрезок; `LessonService.week`/`today` передают Поправки;
      `Week.Day.movedAway`. Проверить сервисным тестом на `TestClock`:
      Встреча, перенесённая из прошлой недели, видна в этой
- [x] 2.3 `schedule/week.html` и `home.html`: пометки «отменена»,
      «перенесена с …», строка «перенесена на …», «не пришёл»; Встреча
      ведёт на свою страницу с `from`. Только классы `locus.css`.
      Проверить веб-тестом через `Browser`

## 3. Действия над Встречей

- [x] 3.1 `LessonService.meeting(id, plannedDate)` и действия `move`,
      `cancel`, `restore`, `markAbsence(absent)` по правилам design.md;
      `MeetingNotFoundException` на дату без Встречи и на чужое Занятие;
      пустая Поправка удаляется. Проверить сервисными тестами на каждый
      отказ: неявка в будущем, у Группы, у отменённой; отмена с неявкой;
      длительность переноса 0
- [x] 3.2 `ScheduleController`: `GET /schedule/lessons/{id}/meetings/{date}`
      и `POST .../move`, `.../cancellation`, `.../restoration`,
      `.../absence`; шаблон `schedule/meeting.html` с блоками действий
      (ADR-0042: при отказе раскрыт блок своего действия, «Отмена»,
      возврат `from`). Проверить веб-тестом проход «перенёс — увидел
      в другой неделе — вернул — отметил неявку — снял»
- [x] 3.3 Изоляция: `LessonsAreFilteredByOwnerTest` дополнить — Учитель Б
      не открывает Встречу Учителя А и не поправляет её, Поправки А не
      видны Б. Проверить: тест зелёный

## 4. Поправки при правке и удалении Занятия

- [x] 4.1 `LessonService.change`: при делении Поправки с даты правки
      переходят к новой части; после правки снимаются Поправки, которых
      правило не даёт (у обеих частей). Проверить тестами сценарии
      спеки: отмена переходит при смене времени, снимается при смене дня,
      Поправка до даты правки остаётся; правка последней даты снимает
      Поправки после неё; разовое с новой датой теряет Поправку
- [x] 4.2 `schedule/lesson.html`: в блоке «Изменить» — постоянное
      предупреждение о снятии Поправок; в подтверждении удаления —
      про неявки. Проверить веб-тестом: удаление Занятия с переносом
      в другую неделю убирает и перенесённую Встречу

## 5. Документы и версия

- [ ] 5.1 `glossary.md`: **Поправка Встречи** (`MeetingAdjustment`),
      **плановая дата** (`plannedDate`), неявка; Встреча — с учётом
      Поправки. Проверить: термины совпадают с кодом
- [ ] 5.2 `domain-model.md`: Поправка на схеме и в описании, граница
      (личное), операции «Правка Занятия» (Поправки) и «Удаление Занятия»;
      строка «Встречи недели» в «Вычисляемом». `scenarios.md`: шаги
      сценария недели про перенос, отмену и неявку вместо «ещё не построены»
- [ ] 5.3 `backlog.md`: карточка `schedule-changes` — итог; `CLAUDE.md`:
      абзац статуса, ADR-0048 в сводке, строка расписания в таблице
      сценариев. Проверить: `python3 openspec/context/adr/build_index.py --check`
- [ ] 5.4 Приёмка: `mvn clean package` зелёный
- [ ] 5.5 Поднять версию в `pom.xml` до `2.1.0` и дописать в `CHANGELOG.md`
      строку из `proposal.md`, раздел «Версия»

## Workflow follow-up

- `/opsx:archive schedule-changes`: ADR-0048 — из «Предложено» в «Принято»
  (если владелец принял на гейте), `build_index.py`, проверка горизонта
  редакции 2 в `strategy.md` (признак готовности требует живой недели
  учителя — сам по себе код его не достигает), отметка ✅ строки
  `schedule-changes`, тег `v2.1.0` на коммит архивации.
- Squash в `develop`.
