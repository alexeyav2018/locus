## 1. Ориентация без сведений о съёмке

- [ ] 1.1 Тест в `PdfAssemblyTest`: JPEG с JFIF-маркером и каналами, пронумерованными от нуля (без EXIF), собирается в одностраничный PDF размером с картинку, и картинка вложена байт в байт; JPEG готовится в тесте из записанного ImageIO заменой номеров каналов в SOF и SOS; тест красный до правки с сообщением «не разбирается»
- [ ] 1.2 Тест в `PdfAssemblyTest`: файл с сигнатурой JPEG и испорченным телом отклоняется; выяснить, где именно он отклоняется до правки, и записать итог в `design.md`, раздел «Decisions»
- [ ] 1.3 Править `PdfAssembly.orientationOf`: ошибка чтения сведений о съёмке даёт `TOP_LEFT`; если по итогу 1.2 разбираемость проверялась только там — оставить её отдельным шагом; javadoc `imagePage` дополнить; проверка — `get_diagnostics_for_file`
- [ ] 1.4 Тесты 1.1 и 1.2 зелёные, `photoTakenWithARotatedCameraMakesAnUprightPage` зелёный; `mvn test -Dtest='PdfAssemblyTest,CropFrameTest,Assembl*Test'` зелёный
- [ ] 1.5 `mvn clean package` зелёный; документы `openspec/context/` и `CLAUDE.md` не затрагиваются — проверить, что правок нет
- [ ] 1.6 Поднять версию в `pom.xml` до 2.1.2 и дописать в `CHANGELOG.md` строку из `proposal.md`, раздел «Версия»
