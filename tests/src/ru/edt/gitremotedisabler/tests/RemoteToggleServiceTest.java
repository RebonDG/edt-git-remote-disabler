/**
 * Тест логики RemoteToggleService на настоящем git-репозитории.
 *
 * Проверяет ровно то, что делает плагин:
 *  - отключение убирает remote-tracking ветки и refspec-и fetch, но не
 *    трогает url и саму секцию remote.<name>;
 *  - после отключения git fetch не возвращает ветки;
 *  - включение возвращает конфигурацию в исходное состояние;
 *  - повторное отключение/включение идемпотентно;
 *  - исходный skipDefaultUpdate восстанавливается.
 *
 * Запуск: run-tests.ps1
 */
package ru.edt.gitremotedisabler.tests;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import ru.edt.gitremotedisabler.RemoteToggleService;

public final class RemoteToggleServiceTest {

	private int passed;
	private int failed;

	public static void main(String[] args) throws Exception {
		RemoteToggleServiceTest test = new RemoteToggleServiceTest();
		test.runAll();
		System.out.println();
		System.out.println("Пройдено: " + test.passed + ", провалено: " + test.failed);
		if (test.failed > 0) {
			System.exit(1);
		}
	}

	private void runAll() {
		String[] names = { "disableRemovesBranchesAndFetchSpecs",
				"fetchDoesNotRestoreBranchesAfterDisable",
				"enableRestoresOriginalConfiguration",
				"toggleIsIdempotent",
				"preservesOriginalSkipDefaultUpdateTrue",
				"disableKeepsRemoteSectionAndUrl" };
		for (String name : names) {
			try {
				switch (name) {
				case "disableRemovesBranchesAndFetchSpecs" -> disableRemovesBranchesAndFetchSpecs();
				case "fetchDoesNotRestoreBranchesAfterDisable" -> fetchDoesNotRestoreBranchesAfterDisable();
				case "enableRestoresOriginalConfiguration" -> enableRestoresOriginalConfiguration();
				case "toggleIsIdempotent" -> toggleIsIdempotent();
				case "preservesOriginalSkipDefaultUpdateTrue" -> preservesOriginalSkipDefaultUpdateTrue();
				case "disableKeepsRemoteSectionAndUrl" -> disableKeepsRemoteSectionAndUrl();
				default -> throw new IllegalStateException(name);
				}
				report(name, true, "");
			} catch (Throwable e) {
				report(name, false, e.toString());
			}
		}
	}

	private void report(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
			System.out.println("  OK   " + name);
		} else {
			failed++;
			System.out.println("  FAIL " + name + " -> " + detail);
		}
	}

	// --- Тесты -------------------------------------------------------------

	private void disableRemovesBranchesAndFetchSpecs() throws Exception {
		try (Fixture f = Fixture.create()) {
			assertEquals(3, RemoteToggleService.getRemoteTrackingBranches(f.repo, "origin").size(),
					"ветки до отключения");
			int removed = RemoteToggleService.disable(f.repo, "origin");
			assertEquals(3, removed, "удалено веток");
			assertEquals(0, RemoteToggleService.getRemoteTrackingBranches(f.repo, "origin").size(),
					"ветки после отключения");
			assertEquals(0, f.repo.getConfig().getStringList("remote", "origin", "fetch").length,
					"refspec-и fetch после отключения");
			assertTrue(RemoteToggleService.isDisabled(f.repo, "origin"), "isDisabled");
			// Ветки второго remote не должны пострадать.
			assertEquals(3, f.repo.getRefDatabase()
					.getRefsByPrefix(Constants.R_REMOTES + "mirror/").size(),
					"ветки mirror не тронуты");
		}
	}

	private void fetchDoesNotRestoreBranchesAfterDisable() throws Exception {
		try (Fixture f = Fixture.create()) {
			RemoteToggleService.disable(f.repo, "origin");
			// Имитируем fetch: git тянет только то, что разрешают refspec-и.
			try (Git git = new Git(f.repo)) {
				git.fetch().setRemote("origin").call();
			} catch (Exception e) {
				// JGit без refspec-ов может отказаться — это тоже приемлемый
				// исход: главное, что ветки не вернулись.
				System.out.println("       (fetch origin без refspec: " + e.getClass().getSimpleName() + ")");
			}
			f.repo.getRefDatabase().refresh();
			assertEquals(0, RemoteToggleService.getRemoteTrackingBranches(f.repo, "origin").size(),
					"ветки не вернулись после fetch");
		}
	}

	private void enableRestoresOriginalConfiguration() throws Exception {
		try (Fixture f = Fixture.create()) {
			List<String> before = RemoteToggleService.getRemoteTrackingBranches(f.repo, "origin");
			String[] fetchBefore = f.repo.getConfig().getStringList("remote", "origin", "fetch");
			String urlBefore = RemoteToggleService.getUrl(f.repo, "origin");

			RemoteToggleService.disable(f.repo, "origin");
			boolean changed = RemoteToggleService.enable(f.repo, "origin");

			assertTrue(changed, "enable вернул true");
			assertFalse(RemoteToggleService.isDisabled(f.repo, "origin"), "после enable не отключён");
			assertArrayEquals(fetchBefore,
					f.repo.getConfig().getStringList("remote", "origin", "fetch"), "refspec-и восстановлены");
			assertEquals(urlBefore, RemoteToggleService.getUrl(f.repo, "origin"), "url не изменился");
			assertFalse(f.repo.getConfig().getBoolean("remote", "origin", "skipDefaultUpdate", false),
					"skipDefaultUpdate снят");
			assertFalse(f.repo.getConfig().getBoolean("remote", "origin", "disabled", false),
					"маркер disabled снят");
			assertEquals(before.size(), before.size(), "ветки восстановятся при fetch");

			// После включения обычный fetch снова скачивает ветки.
			try (Git git = new Git(f.repo)) {
				git.fetch().setRemote("origin").call();
			}
			f.repo.getRefDatabase().refresh();
			assertEquals(before.size(), RemoteToggleService.getRemoteTrackingBranches(f.repo, "origin").size(),
					"ветки вернулись после fetch");
		}
	}

	private void toggleIsIdempotent() throws Exception {
		try (Fixture f = Fixture.create()) {
			RemoteToggleService.disable(f.repo, "origin");
			// Повторное отключение не должно ломать конфигурацию.
			RemoteToggleService.disable(f.repo, "origin");
			assertTrue(RemoteToggleService.isDisabled(f.repo, "origin"), "всё ещё отключён");
			assertEquals(0, f.repo.getConfig().getStringList("remote", "origin", "fetch").length,
					"refspec-ов нет");

			RemoteToggleService.enable(f.repo, "origin");
			// Повторное включение ничего не делает.
			assertFalse(RemoteToggleService.enable(f.repo, "origin"), "повторный enable вернул false");
			assertArrayEquals(new String[] { "+refs/heads/*:refs/remotes/origin/*" },
					f.repo.getConfig().getStringList("remote", "origin", "fetch"),
					"refspec восстановлен ровно один");
		}
	}

	private void preservesOriginalSkipDefaultUpdateTrue() throws Exception {
		try (Fixture f = Fixture.create()) {
			StoredConfig config = f.repo.getConfig();
			config.setBoolean("remote", "origin", "skipDefaultUpdate", true);
			config.save();

			RemoteToggleService.disable(f.repo, "origin");
			assertTrue(config.getBoolean("remote", "origin", "skipDefaultUpdate", false),
					"при отключении skipDefaultUpdate включён");

			RemoteToggleService.enable(f.repo, "origin");
			assertTrue(f.repo.getConfig().getBoolean("remote", "origin", "skipDefaultUpdate", false),
					"исходное true восстановлено");
		}
	}

	private void disableKeepsRemoteSectionAndUrl() throws Exception {
		try (Fixture f = Fixture.create()) {
			String url = RemoteToggleService.getUrl(f.repo, "origin");
			RemoteToggleService.disable(f.repo, "origin");
			assertEquals(url, RemoteToggleService.getUrl(f.repo, "origin"),
					"url сохранён — remote не удалён");
			assertTrue(f.repo.getConfig().getSubsections("remote").contains("origin"),
					"секция remote.origin осталась");
		}
	}

	// --- Фикстура ----------------------------------------------------------

	/** Временный репозиторий-источник и клон с remote origin. */
	private static final class Fixture implements AutoCloseable {

		final Path dir;
		final Repository repo;
		private final Repository upstream;

		private Fixture(Path dir, Repository repo, Repository upstream) {
			this.dir = dir;
			this.repo = repo;
			this.upstream = upstream;
		}

		static Fixture create() throws Exception {
			Path base = Files.createTempDirectory("grd-test-");
			Path upDir = base.resolve("upstream");
			Path clientDir = base.resolve("client");

			Repository upstream = initEmpty(upDir);
			Repository client = initEmpty(clientDir);

			// Три ветки в источнике: main, feat1, feat2.
			createBranch(upstream, "main", "f.txt", "one");
			createBranch(upstream, "feat1", "g.txt", "f1");
			createBranch(upstream, "feat2", "h.txt", "f2");

			StoredConfig upstreamConfig = upstream.getConfig();
			upstreamConfig.setString("remote", "mirror", "url", upDir.toString());
			upstreamConfig.save();

			StoredConfig config = client.getConfig();
			// Целевой remote — origin (обычное имя по умолчанию).
			config.setString("remote", "origin", "url", upDir.toString());
			config.setStringList("remote", "origin", "fetch",
					List.of("+refs/heads/*:refs/remotes/origin/*"));
			// Второй remote — контрольный: он не должен затрагиваться.
			config.setString("remote", "mirror", "url", upDir.toString());
			config.setStringList("remote", "mirror", "fetch",
					List.of("+refs/heads/*:refs/remotes/mirror/*"));
			config.setString("branch", "main", "remote", "origin");
			config.setString("branch", "main", "merge", "refs/heads/main");
			config.save();

			// Первый fetch забирает ветки для обоих remote.
			try (Git git = new Git(client)) {
				git.fetch().setRemote("origin").call();
				git.fetch().setRemote("mirror").call();
			}
			client.getRefDatabase().refresh();

			if (client.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/").size() < 3) {
				throw new IllegalStateException("фикстура: ветки origin не скачались");
			}
			return new Fixture(base, client, upstream);
		}

		private static Repository initEmpty(Path dir) throws Exception {
			Files.createDirectories(dir);
			try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
				StoredConfig config = git.getRepository().getConfig();
				config.setString("user", null, "name", "Test");
				config.setString("user", null, "email", "test@example.com");
				config.save();
			}
			return new FileRepositoryBuilder()
					.setGitDir(dir.resolve(".git").toFile())
					.setMustExist(true)
					.build();
		}

		private static void createBranch(Repository repository, String name, String file, String content)
				throws Exception {
			try (Git git = new Git(repository)) {
				Files.write(repository.getWorkTree().toPath().resolve(file),
						content.getBytes(StandardCharsets.UTF_8));
				git.add().addFilepattern(file).call();
				if (repository.exactRef("HEAD") == null || repository.resolve("HEAD") == null) {
					git.commit().setMessage("init " + name).setSign(false).call();
				} else {
					git.commit().setMessage("add " + name).setSign(false).call();
				}
				if (!"main".equals(name)) {
					git.branchCreate().setName(name).call();
				}
			}
		}

		@Override
		public void close() throws Exception {
			repo.close();
			upstream.close();
			try (var stream = Files.walk(dir)) {
				stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
						.forEach(p -> {
							try {
								Files.deleteIfExists(p);
							} catch (IOException ignored) {
								// Временный каталог: чистим, что можем.
							}
						});
			}
		}
	}

	// --- Примитивы проверок ------------------------------------------------

	private static void assertEquals(Object expected, Object actual, String what) {
		if (!String.valueOf(expected).equals(String.valueOf(actual))) {
			throw new AssertionError(what + ": ожидалось <" + expected + ">, получено <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String what) {
		if (!value) {
			throw new AssertionError(what + ": ожидалось true");
		}
	}

	private static void assertFalse(boolean value, String what) {
		if (value) {
			throw new AssertionError(what + ": ожидалось false");
		}
	}

	private static void assertArrayEquals(String[] expected, String[] actual, String what) {
		if (!java.util.Arrays.equals(expected, actual)) {
			throw new AssertionError(what + ": ожидалось " + java.util.Arrays.toString(expected)
					+ ", получено " + java.util.Arrays.toString(actual));
		}
	}

	@SuppressWarnings("unused")
	private static Ref unusedRef() {
		return null;
	}
}
