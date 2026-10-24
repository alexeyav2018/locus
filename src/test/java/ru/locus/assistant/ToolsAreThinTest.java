package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.assignment.AssignmentBatchRepository;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.dictionary.CharacteristicRepository;
import ru.locus.dictionary.DictionaryTools;
import ru.locus.dictionary.SolutionMethodRepository;
import ru.locus.file.FileStorage;
import ru.locus.lesson.LessonRepository;
import ru.locus.lesson.MeetingAdjustmentRepository;
import ru.locus.mastery.MasteryRepository;
import ru.locus.problem.AssemblyDraftRepository;
import ru.locus.problem.AssemblyDraftTools;
import ru.locus.problem.ProblemDraftRepository;
import ru.locus.problem.ProblemDraftTools;
import ru.locus.problem.ProblemRepository;
import ru.locus.problem.ProblemTools;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyRepository;
import ru.locus.taxonomy.TaxonomyTools;
import ru.locus.theory.TheoryMaterialRepository;
import ru.locus.user.UserRepository;
import ru.locus.work.StudentWorkRepository;

/**
 * Спека {@code assistant-tools}, сценарий «Инструмент не ходит в репозиторий»;
 * standards.md, «Слои и границы»: инструмент MCP — тонкая обёртка сервиса,
 * как контроллер (ADR-0056).
 *
 * <p>Инструмент, пошедший в репозиторий мимо сервиса, унёс бы с собой
 * {@code @PreAuthorize} и фильтр по владельцу: учитель получил бы через
 * Помощника то, чего не получает на странице, — и тихо. Поэтому запрещены
 * все репозитории системы, а не только своей области: инструментов личного
 * контура у Помощника нет вовсе (ADR-0054), и Ученики, Работы или отметки,
 * прочитанные инструментом библиотеки напрямую, ушли бы наружу в API модели.
 * Хранилище файлов — тем же пунктом, что у контроллеров: файл, положенный
 * или прочитанный мимо сервиса, обошёл бы проверки состава и доступа.
 *
 * <p>Список классов здесь ручной, по образцу {@code ProblemControllerIsThinTest};
 * что в приложении нет реализации {@code Tools} вне этого списка, сторожит
 * {@code ToolRegistryTest.OfTheApplication}.
 */
class ToolsAreThinTest {

    /** Все реализации {@link Tools} приложения — каждая под сторожем. */
    static final List<Class<? extends Tools>> TOOLS = List.of(
            TaxonomyTools.class,
            DictionaryTools.class,
            ProblemTools.class,
            AssemblyDraftTools.class,
            ProblemDraftTools.class);

    /** Все репозитории системы и хранилище файлов — ни один не виден инструменту. */
    private static final List<Class<?>> FORBIDDEN = List.of(
            TaxonomyRepository.class,
            SolutionMethodRepository.class,
            CharacteristicRepository.class,
            ProblemRepository.class,
            AssemblyDraftRepository.class,
            ProblemDraftRepository.class,
            TheoryMaterialRepository.class,
            UserRepository.class,
            StudentRepository.class,
            GroupRepository.class,
            AssignmentRepository.class,
            AssignmentBatchRepository.class,
            StudentWorkRepository.class,
            MasteryRepository.class,
            LessonRepository.class,
            MeetingAdjustmentRepository.class,
            FileStorage.class);

    @Test
    void toolsHoldNeitherRepositoryNorStorage() {
        for (Class<?> tools : TOOLS) {
            for (Field field : tools.getDeclaredFields()) {
                assertThat(FORBIDDEN)
                        .as("поле %s.%s не должно быть репозиторием или хранилищем",
                                tools.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
            }
        }
    }

    @Test
    void toolsAreNotEvenGivenOne() {
        for (Class<?> tools : TOOLS) {
            for (Constructor<?> constructor : tools.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("репозитории и хранилище инструментам %s не передаются", tools.getSimpleName())
                        .doesNotContain(FORBIDDEN.toArray(Class<?>[]::new));
            }
        }
    }
}
