package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.locus.user.UserId;

/**
 * Спека {@code assistant-tools}, «Рабочие места учителей разные»;
 * design.md agent-runner, Р7: рабочее место разворачивается заново перед
 * каждой Репликой, каталог настроек с сеансами Claude Code остаётся.
 */
class AgentWorkspacesTest {

    @TempDir
    Path root;

    @Test
    void twoTeachersGetDifferentWorkAndConfig() {
        AgentWorkspaces workspaces = new AgentWorkspaces(root);

        AgentWorkspaces.Workspace first = workspaces.prepare(new UserId(1));
        AgentWorkspaces.Workspace second = workspaces.prepare(new UserId(2));

        assertThat(first.work()).isNotEqualTo(second.work());
        assertThat(first.config()).isNotEqualTo(second.config());
        assertThat(first.config()).isDirectory();
        assertThat(second.config()).isDirectory();
        assertThat(first.work()).startsWith(root);
    }

    @Test
    void workspaceCarriesRulesServerAndPermissions() {
        AgentWorkspaces.Workspace workspace = new AgentWorkspaces(root).prepare(new UserId(7));

        assertThat(workspace.work().resolve(".mcp.json")).content().contains("${LOCUS_TURN_TOKEN}");
        assertThat(workspace.work().resolve(".claude/settings.json")).content().contains("mcp__locus__*");
        assertThat(workspace.work().resolve("CLAUDE.md")).isRegularFile();
    }

    @Test
    void preparingAgainRemovesStrayFilesButKeepsSessions() throws Exception {
        AgentWorkspaces workspaces = new AgentWorkspaces(root);
        AgentWorkspaces.Workspace before = workspaces.prepare(new UserId(3));
        Files.writeString(before.work().resolve("left-by-old-version.md"), "устарело");
        Files.writeString(before.work().resolve(".mcp.json"), "{}");
        Files.writeString(before.config().resolve("session.jsonl"), "сеанс");

        AgentWorkspaces.Workspace after = workspaces.prepare(new UserId(3));

        assertThat(after).isEqualTo(before);
        assertThat(after.work().resolve("left-by-old-version.md")).doesNotExist();
        assertThat(after.work().resolve(".mcp.json")).content().contains("locus");
        assertThat(after.config().resolve("session.jsonl")).content().isEqualTo("сеанс");
    }
}
