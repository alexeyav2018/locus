package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyRepository;
import ru.locus.user.Role;

/**
 * Сценарий «Отказ раскрывает нужный блок» (interface-navigation): сообщение,
 * раскрытый блок именно отклонённого действия, введённое значение на месте,
 * прочие блоки свёрнуты.
 */
class RefusalKeepsInputTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TaxonomyRepository nodes;

    @Test
    void renamingANodeToATakenNameOpensTheRenameBlockWithTheInput() {
        String taken = TestLibrary.unique("Алгебра");
        nodes.create(taken, null);
        TaxonomyNodeId other = nodes.create(TestLibrary.unique("Геометрия"), null);
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser administrator = new Browser(port);
        administrator.logIn(account.login(), account.password());

        Browser.Page refused = administrator.postForm("/taxonomy/" + other.value() + "/name", Map.of("name", taken));

        assertThat(refused.body()).contains("class=\"alert\"");
        assertThat(count(refused.body(), "open=\"open\"")).as("раскрыт ровно один блок").isEqualTo(1);
        assertThat(refused.body())
                .containsPattern("(?s)open=\"open\">\\s*<summary>Изменить имя</summary>.*?value=\"" + taken + "\"");
    }

    @Test
    void creatingARootNodeWithATakenNameOpensTheRootBlockOnly() {
        String taken = TestLibrary.unique("Алгебра");
        nodes.create(taken, null);
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser administrator = new Browser(port);
        administrator.logIn(account.login(), account.password());

        Browser.Page refused = administrator.postForm("/taxonomy", Map.of("name", taken));

        assertThat(count(refused.body(), "open=\"open\"")).isEqualTo(1);
        assertThat(refused.body())
                .containsPattern("(?s)open=\"open\">\\s*<summary>Завести корневой узел</summary>.*?value=\"" + taken + "\"");
    }

    private static int count(String body, String fragment) {
        int found = 0;
        for (int at = body.indexOf(fragment); at >= 0; at = body.indexOf(fragment, at + 1)) {
            found++;
        }
        return found;
    }
}
