package ru.locus.mastery;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.DictionaryUsage;
import ru.locus.dictionary.SolutionMethodId;

/**
 * Ответ отметок Владения на вопрос словарей «употреблена ли эта запись».
 *
 * Этим долг словарей погашен до конца: ячейка владения стоит на паре
 * «Тема × Метод» (ADR-0011), и удаление Метода стёрло бы основание
 * суждений учителей об учениках, которых удаляющий не видит, — тихо
 * и невосполнимо (ADR-0010, antipatterns.md, «Молчаливое удаление Темы»).
 * Второй рубеж за этим ответом — ключ {@code fk_mastery_solution_method}
 * без каскада.
 *
 * <p>Считаются отметки <b>всех</b> Учителей: это вопрос библиотеки
 * к личным контурам о своей сущности, и по владельцу он не фильтруется
 * (ADR-0036); наружу — только число. Считаются суждения: «неизвестно»
 * строки не имеет (ADR-0039).
 *
 * <p>Характеристика в измерении владения не участвует (glossary.md):
 * на второй вопрос ответ всегда пуст.
 */
@Component
public class MasteryOfMethod implements DictionaryUsage {

    private final MasteryRepository marks;

    public MasteryOfMethod(MasteryRepository marks) {
        this.marks = marks;
    }

    @Override
    public Optional<String> ofMethod(SolutionMethodId method) {
        int count = marks.countByMethod(method);
        return count == 0 ? Optional.empty() : Optional.of("на него опираются отметки Владения (" + count + ")");
    }

    @Override
    public Optional<String> ofCharacteristic(CharacteristicId characteristic) {
        // Ячейка владения — пара «Тема × Метод»; Характеристики в ней нет,
        // и держать её отметкам нечем.
        return Optional.empty();
    }
}
