package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Требование «Главное на виду, вторичное — по кнопке на той же странице»
 * на карточке Ученика и в списке: правка свёрнута, отказ раскрывает нужный блок.
 */
class FoldsOnStudentScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    /** Сценарий «Правка не мешает чтению». */
    @Test
    void cardKeepsEditingFoldedAway() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        StudentId student = students.create(teacher.id(), "Иванов Пётр");

        String body = loggedIn(teacher).get("/students/" + student.value()).body();

        assertThat(body).contains("Иванов Пётр").contains("Владение").contains("Работы Ученика");
        assertThat(opened(body)).as("ни один блок не раскрыт").isZero();
        assertThat(body).contains("<summary>Изменить имя</summary>").contains("<summary>Удаление</summary>");
        assertThat(body).as("блоки действий исключают друг друга").contains("name=\"student-actions\"");
    }

    /** Сценарий «Отказ раскрывает нужный блок». */
    @Test
    void refusedRenameOpensTheRenameBlockAlone() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        StudentId student = students.create(teacher.id(), "Иванов Пётр");

        Browser.Page refused = loggedIn(teacher)
                .postForm("/students/" + student.value() + "/name", Map.of("name", " "));

        assertThat(refused.body()).contains("Имя Ученика не может быть пустым");
        assertThat(opened(refused.body())).as("раскрыт ровно один блок").isEqualTo(1);
        assertThat(refused.body()).containsPattern("(?s)<details class=\"more\" name=\"student-actions\" open=\"open\">\\s*<summary>Изменить имя");
    }

    @Test
    void refusedCreationOpensTheCreationBlockOnTheList() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);

        Browser.Page refused = loggedIn(teacher).postForm("/students", Map.of("name", " "));

        assertThat(refused.body()).containsPattern("(?s)open=\"open\">\\s*<summary>Завести Ученика");
    }

    private static int opened(String body) {
        int count = 0;
        for (int at = body.indexOf("open=\"open\""); at >= 0; at = body.indexOf("open=\"open\"", at + 1)) {
            count++;
        }
        return count;
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
