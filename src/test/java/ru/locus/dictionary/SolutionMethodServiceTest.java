package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.user.Role;

/**
 * Правила словаря Методов: заведение, переименование и удаление записи.
 *
 * Все проверки стоят в сервисе, поэтому и проверяются на сервисе, а не через
 * экран: правило должно срабатывать при любом способе вызова, включая
 * контроллер, о котором сейчас никто не думает.
 */
class SolutionMethodServiceTest extends IntegrationTest {

    @Autowired
    private SolutionMethodService methods;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Запись заведена». */
    @Test
    void createdMethodAppearsInItsDictionary() {
        String name = unique("Разложение на множители");

        SolutionMethodId id = methods.create(name);

        assertThat(methods.method(id).name()).isEqualTo(name);
        assertThat(methods.all()).extracting(SolutionMethod::id).contains(id);
    }

    /** Сценарий «Запись заводится одним именем». */
    @Test
    void nothingButANameIsAskedForAtCreation() {
        assertThat(SolutionMethodService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("create"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .as("узел рубрикатора при заведении Метода указать невозможно (ADR-0010)")
                        .containsExactly(String.class));
    }

    /** Сценарий «Пустое имя». */
    @Test
    void emptyNameCreatesNothing() {
        assertThatThrownBy(() -> methods.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> methods.create("")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Сценарий «Пробелы по краям имени отсекаются». */
    @Test
    void surroundingSpacesAreTrimmedAndTheTrimmedNameIsTaken() {
        String name = unique("Разложение на множители");

        SolutionMethodId id = methods.create("   " + name + "   ");

        assertThat(methods.method(id).name()).isEqualTo(name);
        assertThatThrownBy(() -> methods.create(name))
                .as("повторное заведение того же имени отклоняется как занятое")
                .isInstanceOf(NameAlreadyTakenException.class);
    }

    /** Сценарий «Одноимённые Методы не сосуществуют». */
    @Test
    void sameNameIsRefused() {
        String name = unique("Разложение на множители");
        methods.create(name);

        assertThatThrownBy(() -> methods.create(name))
                .isInstanceOf(NameAlreadyTakenException.class)
                .hasMessageContaining("занято");
    }

    /**
     * Занятость имени проверяется без учёта регистра — тем же правилом, что
     * держит индекс. Разойдись они, сервис пропустил бы имя, на котором
     * падает база.
     */
    @Test
    void sameNameInAnotherCaseIsRefusedByTheServiceItself() {
        String name = unique("Разложение на множители");
        methods.create(name);

        assertThatThrownBy(() -> methods.create(name.toUpperCase()))
                .isInstanceOf(NameAlreadyTakenException.class);
    }

    /** Сценарий «Запись переименована». */
    @Test
    void renamedMethodKeepsItsIdentifierAndLeavesTheRestAlone() {
        SolutionMethodId renamed = methods.create(unique("Разложение на множители"));
        SolutionMethodId untouched = methods.create(unique("Замена переменной"));
        String untouchedName = methods.method(untouched).name();
        String newName = unique("Вынесение общего множителя");

        methods.rename(renamed, newName);

        assertThat(methods.method(renamed).name()).isEqualTo(newName);
        assertThat(methods.method(untouched).name()).isEqualTo(untouchedName);
    }

    /** Сценарий «Новое имя занято». */
    @Test
    void renamingToATakenNameChangesNothing() {
        String taken = unique("Замена переменной");
        methods.create(taken);
        String name = unique("Разложение на множители");
        SolutionMethodId id = methods.create(name);

        assertThatThrownBy(() -> methods.rename(id, taken)).isInstanceOf(NameAlreadyTakenException.class);

        assertThat(methods.method(id).name()).isEqualTo(name);
    }

    /** Сценарий «Запись переименована в собственное имя». */
    @Test
    void savingAMethodUnderItsOwnNameGoesThrough() {
        String name = unique("Разложение на множители");
        SolutionMethodId id = methods.create(name);

        assertThatCode(() -> methods.rename(id, name))
                .as("сохранение под собственным именем не должно спотыкаться о проверку занятости")
                .doesNotThrowAnyException();

        assertThat(methods.method(id).name()).isEqualTo(name);
    }

    /** Сценарий «Неиспользуемая запись удалена». */
    @Test
    void unusedMethodIsDeletedAndTheRestStay() {
        SolutionMethodId gone = methods.create(unique("Разложение на множители"));
        SolutionMethodId kept = methods.create(unique("Замена переменной"));

        methods.delete(gone);

        assertThat(methods.all()).extracting(SolutionMethod::id).doesNotContain(gone).contains(kept);
        assertThatThrownBy(() -> methods.method(gone)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownIdentifierIsRefusedByEveryOperation() {
        long free = methods.all().stream().mapToLong(method -> method.id().value()).max().orElse(0) + 1_000_000;
        SolutionMethodId missing = new SolutionMethodId(free);

        assertThatThrownBy(() -> methods.method(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> methods.rename(missing, "Что угодно")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> methods.delete(missing)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
