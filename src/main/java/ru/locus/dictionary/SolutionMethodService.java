package ru.locus.dictionary;

import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ведение словаря Методов: чтение словаря и правила его правки.
 *
 * Права проверяются здесь, а не в контроллере и не на адресах (standards.md,
 * «Слои и границы»): правило привязано к операции и срабатывает при любом
 * способе вызова. Правит словарь только Администратор; читают его все
 * вошедшие — вход уже потребован грубым рубежом в {@code SecurityConfig},
 * а роли для чтения общей библиотеки не важны.
 *
 * Словарь — общая библиотека, и по владельцу он <b>не фильтруется</b>:
 * все Учителя видят один и тот же список (ADR-0004, ADR-0027). Параметра
 * владельца нет ни здесь, ни в репозитории.
 */
@Service
public class SolutionMethodService {

    private final SolutionMethodRepository methods;

    /**
     * Области, ссылающиеся на записи словаря, — источник ответа на вопрос
     * «употреблена ли запись». Собираются списком: словарь не знает ни одной
     * из них по имени, и появление следующей его не касается.
     */
    private final List<DictionaryUsage> usages;

    public SolutionMethodService(SolutionMethodRepository methods, List<DictionaryUsage> usages) {
        this.methods = methods;
        this.usages = usages;
    }

    /** Весь словарь, по алфавиту. */
    public List<SolutionMethod> all() {
        return methods.findAll();
    }

    public SolutionMethod method(SolutionMethodId id) {
        return existing(id);
    }

    /** Заводит Метод. Имя обязательно, не пустое и не занятое. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public SolutionMethodId create(String name) {
        String trimmed = requireName(name);
        refuseIfNameTaken(trimmed, null);
        return methods.create(trimmed);
    }

    /**
     * Меняет имя Метода. Идентификатор при этом не меняется, и разметка,
     * ссылающаяся на запись, за именем не следует (ADR-0007) — поэтому
     * ограничений, кроме требований к самому имени, нет.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void rename(SolutionMethodId id, String newName) {
        SolutionMethod method = existing(id);
        String trimmed = requireName(newName);
        refuseIfNameTaken(trimmed, method.id());
        methods.rename(method.id(), trimmed);
    }

    /** Снимает Метод, если он не используется. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(SolutionMethodId id) {
        SolutionMethod method = existing(id);
        refuseUnlessUnused(method);
        methods.delete(method.id());
    }

    /**
     * Единственная проверка того, что запись не используется: сюда
     * дописывается каждое новое условие, и искать его потом надо в одном
     * месте, а не по всем вызовам удаления.
     *
     * <p>Условие спрашивается у {@link DictionaryUsage} — вопроса, на который
     * отвечают области, ссылающиеся на записи словаря; сам сервис ни одну
     * из них не знает по имени (design.md, «Проверки в чужих областях»).
     *
     * <p><b>Появление каждой из двух сущностей обязано пополнить эту
     * проверку</b>:
     *
     * <ul>
     *   <li>разметка Задачи — связь {@code problem_solution_method};
     *       <b>пришла</b> с работой {@code problem-catalog}, отвечает
     *       {@code ProblemMarkupUsage};</li>
     *   <li>отметки Владения — на паре «Тема × Метод», их ячейки опираются
     *       на удаляемый Метод; работа {@code mastery-marks}.</li>
     * </ul>
     *
     * <p>Забытое пополнение — тихая потеря данных: удаление употреблённого
     * Метода стирает основание ячеек владения у всех учителей сразу, то есть
     * их суждений об учениках, которых удаляющий не видит, и восстановить их
     * неоткуда (ADR-0010, antipatterns.md, «Молчаливое удаление Темы»).
     *
     * <p>Что делать с осиротевшими ячейками при правке Методов у уже
     * размеченной Задачи — вопрос не этой возможности: он решается элементом
     * бэклога {@code method-edit-impact}.
     */
    private void refuseUnlessUnused(SolutionMethod method) {
        for (DictionaryUsage usage : usages) {
            Optional<String> used = usage.ofMethod(method.id());
            if (used.isPresent()) {
                throw new EntryInUseException("Метод «" + method.name() + "» используется: "
                        + used.get() + ". Пока это так, удалить его нельзя");
            }
        }
    }

    /**
     * Имя не должно повторяться в словаре — сравнение без учёта регистра,
     * тем же правилом, что держит уникальный индекс в схеме.
     *
     * @param keep запись, которая сама себе дублем не считается
     *             (переименование, в том числе в собственное имя), либо
     *             {@code null}
     */
    private void refuseIfNameTaken(String name, SolutionMethodId keep) {
        boolean taken = methods.findByName(name)
                .filter(other -> !other.id().equals(keep))
                .isPresent();
        if (taken) {
            throw new NameAlreadyTakenException(name);
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя Метода не может быть пустым");
        }
        return trimmed;
    }

    private SolutionMethod existing(SolutionMethodId id) {
        return methods.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Метода с идентификатором " + id.value() + " не существует"));
    }
}
