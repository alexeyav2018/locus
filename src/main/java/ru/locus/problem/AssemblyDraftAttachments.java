package ru.locus.problem;

import org.springframework.stereotype.Component;
import ru.locus.assistant.TurnAttachment;
import ru.locus.assistant.TurnAttachments;

/**
 * Ответ области Задач на вопрос {@link TurnAttachments}: файл из реплики
 * становится Черновиком сборки вошедшего — тем же
 * {@link AssemblyDraftService#upload}, что у формы Задачи, с его правами
 * (Администратор), распознаванием вида и отказами (design.md
 * assistant-chat, Р5).
 *
 * <p>Помощник получает номер черновика: по нему он смотрит страницы
 * и режет куски инструментами черновиков ({@link AssemblyDraftTools}).
 */
@Component
public class AssemblyDraftAttachments implements TurnAttachments {

    private final AssemblyDraftService drafts;

    public AssemblyDraftAttachments(AssemblyDraftService drafts) {
        this.drafts = drafts;
    }

    @Override
    public String attach(TurnAttachment file) {
        AssemblyDraft draft = drafts.upload(file.name(), file.content());
        return "Загружен черновик сборки " + draft.id().value() + " «" + draft.originalName()
                + "», страниц: " + draft.pageCount() + ".";
    }
}
