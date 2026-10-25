package ru.locus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.catalina.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import ru.locus.IntegrationTest;

/**
 * Требование «Сеанс истекает после двух часов бездействия».
 *
 * Проверяется срок, который получил контейнер работающего сервера, а не
 * свойство настройки: опечатка в имени свойства или его перенос в другой
 * раздел оставили бы свойство непрочитанным, и контейнер тихо взял бы своё
 * умолчание в 30 минут. Ждать два часа тест не может — само истечение
 * остаётся поведением контейнера.
 */
class SessionLifetimeTest extends IntegrationTest {

    @Autowired
    private WebServerApplicationContext context;

    @Test
    void theServerKeepsASessionForTwoHoursOfInactivity() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();

        assertThat(Arrays.stream(server.getTomcat().getHost().findChildren()))
                .as("у приложения один контекст Tomcat")
                .singleElement()
                .extracting(child -> ((Context) child).getSessionTimeout())
                .as("срок сессии в минутах")
                .isEqualTo(120);
    }
}
