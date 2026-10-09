# Tasks

Документы `openspec/context/`, которые изменение затрагивает: `ux-concept.md`
(абзац о сцене рамки: масштаб, режим, ручки). `glossary.md`,
`domain-model.md`, `scenarios.md`, `architecture.md`, `strategy.md`,
`standards.md`, `antipatterns.md`, журнал решений — не затрагивают.
`CLAUDE.md` — статус при архивации.

## 1. Крупный показ страницы на сервере

- [ ] 1.1 `PdfAssembly.preview` — параметр `boolean large`: крупный показ —
      длинная сторона до 3200 px (`LARGE_PREVIEW_LONG_SIDE`), страница PDF
      до 300 dpi (`LARGE_PREVIEW_DPI`); обычный прежний; картинка
      не увеличивается; javadoc и ссылка на design.md, «Крупный показ»
- [ ] 1.2 `AssemblyDraftService.preview(id, page, large)` и
      `AssemblyDraftController.page` — `@RequestParam(defaultValue = "false") boolean large`
- [ ] 1.3 `PdfAssemblyTest`: крупный показ A4 — длинная сторона 3200;
      мелкой страницы — предел 300 dpi; большой картинки — 3200; мелкая
      картинка не увеличивается
- [ ] 1.4 `AssemblyScreenTest`: `/pages/2?large=true` — JPEG крупнее
      обычного; чужой черновик и страница вне черновика с `large` — 404
- [ ] 1.5 `mvn test -Dtest='PdfAssemblyTest,AssemblyScreenTest,DraftsAreFilteredByOwnerTest,AssemblyDraftServiceTest'` зелёный

## 2. Масштаб, режим и ручки на сцене

- [ ] 2.1 `locus.js`: сцена — панель («−», подпись масштаба, «+»,
      «Рисовать | Двигать») и окно `.crop-viewport` с `.crop-canvas`;
      ступени 100–400 %, ширина холста от вписанной, середина видимого
      места сохраняется; при масштабе > 100 % картинка — `?large=true`
- [ ] 2.2 `locus.js`: режим «Двигать» — класс холста, мышь тянет
      прокрутку окна; «Рисовать» — прежнее рисование
- [ ] 2.3 `locus.js`: восемь ручек `.crop-handle` у рамки, тянутся
      в любом режиме, нормализация min/max, пределы страницы и порог 0,5 %,
      запись долей в скрытое поле
- [ ] 2.4 `locus.css`: панель, окно с прокруткой и пределом 80vh,
      `touch-action` по режиму, ручки с полем касания ≥ 28 px; кнопки
      панели не ниже 44 px (ux-concept, раздел 7)
- [ ] 2.5 `AssemblyScreenTest` (или проверка `locus.js`/`locus.css`
      в ресурсах): скрипт строит панель масштаба и ручки — есть классы
      и адрес с `large=true`
- [ ] 2.6 Проверить руками в браузере (Chrome, ширина планшета и 390 px):
      масштаб, прокрутка в «Двигать», рамка в «Рисовать», угол тянется,
      скрытое поле получает новые доли

## 3. Документы и приёмка

- [ ] 3.1 `ux-concept.md`: абзац о сцене рамки — масштаб «−/+», режим
      «Рисовать | Двигать», ручки
- [ ] 3.2 `mvn clean package` зелёный
- [ ] 3.3 Поднять версию в `pom.xml` до 3.2.0 и дописать в `CHANGELOG.md`
      строку из `proposal.md`, раздел «Версия»
