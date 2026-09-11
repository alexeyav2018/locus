package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Ученик — не Пользователь системы (ADR-0003): он объект учёта, и учётной
 * записи у него нет — ни имени входа, ни пароля, ни ролей.
 *
 * Проверяется с двух сторон. Со стороны схемы: в таблице {@code student}
 * ровно три колонки, и ни одна из них не про вход — миграция, добавившая
 * Ученику пароль «на будущее», уронит этот тест. Со стороны входа: имя
 * Ученика в форме входа отклоняется точно так же, как несуществующее имя,
 * — отказ неотличим, и по нему нельзя понять, что такой Ученик есть.
 */
class StudentIsNotAUserTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient database;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    /** У Ученика есть идентификатор, владелец и имя — и ничего для входа. */
    @Test
    void theStudentTableCarriesNothingToLogInWith() {
        List<String> columns = database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'student'
                        """)
                .query(String.class)
                .list();

        assertThat(columns)
                .as("ровно три колонки: ни имени входа, ни пароля, ни признака смены пароля")
                .containsExactlyInAnyOrder("id", "user_id", "name");
    }

    /** Имя Ученика в форме входа — просто несуществующее имя. */
    @Test
    void loggingInUnderAStudentsNameIsRefusedLikeAnUnknownLogin() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        students.create(teacher.id(), "Иванов Пётр");

        Browser asStudent = new Browser(port);
        Browser.Page attempt = asStudent.logIn("Иванов Пётр", TestAccounts.PASSWORD);

        assertThat(attempt.redirectsTo("/login?error")).as("вход отклонён").isTrue();
        assertThat(asStudent.get("/").redirectsTo("/login")).as("сеанс не начат").isTrue();
        assertThat(refusalMessage("Иванов Пётр", TestAccounts.PASSWORD))
                .as("по сообщению нельзя понять, что такой Ученик заведён")
                .isEqualTo(refusalMessage("такого-имени-нет", TestAccounts.PASSWORD));
    }

    /**
     * Текст, который видит человек после отказа, — как в
     * {@link ru.locus.security.LoginTest}: разметку целиком сравнивать
     * нельзя из-за токена CSRF.
     */
    private String refusalMessage(String login, String password) {
        Browser browser = new Browser(port);
        browser.logIn(login, password);
        Browser.Page form = browser.get("/login?error");

        Matcher paragraph = Pattern.compile("<p>([^<]+)</p>").matcher(form.body());
        assertThat(paragraph.find()).as("отказ показан человеку").isTrue();
        return paragraph.group(1).trim();
    }
}
