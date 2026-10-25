package ru.locus.problem;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import ru.locus.assistant.TurnAttachment;
import ru.locus.assistant.TurnAttachments;

/**
 * Ответ области Задач на вопрос {@link TurnAttachments}: файлы из реплики
 * становятся Черновиками сборки вошедшего — тем же
 * {@link AssemblyDraftService#upload}, что у формы Задачи, с его правами
 * (Администратор), распознаванием вида и отказами (design.md
 * assistant-chat, Р5).
 *
 * <p>Файлы принимаются все или ни одного; отказ называет поле
 * отклонённого файла (design.md assistant-multiple-files, Р2, Р3).
 * Помощник получает по каждому файлу номер поля и номер черновика: по нему
 * он смотрит страницы и режет куски инструментами черновиков
 * ({@link AssemblyDraftTools}).
 */
@Component
public class AssemblyDraftAttachments implements TurnAttachments {

    private final AssemblyDraftService drafts;

    public AssemblyDraftAttachments(AssemblyDraftService drafts) {
        this.drafts = drafts;
    }

    @Override
    public List<String> attach(List<TurnAttachment> files) {
        List<AssemblyDraft> created;
        try {
            created = drafts.upload(files.stream()
                    .map(file -> new AssemblyDraftService.Source(file.name(), file.content()))
                    .toList());
        } catch (SourceRefusedException refusal) {
            throw new IllegalArgumentException(files.get(refusal.position()).label() + ": " + refusal.getMessage(),
                    refusal.getCause());
        }
        return IntStream.range(0, files.size())
                .mapToObj(i -> files.get(i).label() + ": загружен черновик сборки " + created.get(i).id().value()
                        + " «" + created.get(i).originalName() + "», страниц: " + created.get(i).pageCount() + ".")
                .toList();
    }
}
