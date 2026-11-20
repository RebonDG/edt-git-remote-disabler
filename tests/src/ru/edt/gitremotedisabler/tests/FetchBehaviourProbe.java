package ru.edt.gitremotedisabler.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Диагностика: как JGit ведёт себя при fetch отключённого remote в разных
 * вариантах вызова. Нужна, чтобы понять, увидит ли пользователь ошибку EGit,
 * нажав «Получить (fetch)» на отключённом репозитории.
 */
public final class FetchBehaviourProbe {

	public static void main(String[] args) throws Exception {
		Path base = Files.createTempDirectory("grd-probe-");
		Path upDir = base.resolve("upstream");
		Path clientDir = base.resolve("client");

		try (Git upstream = Git.init().setDirectory(upDir.toFile()).setInitialBranch("main").call()) {
			StoredConfig c = upstream.getRepository().getConfig();
			c.setString("user", null, "name", "T");
			c.setString("user", null, "email", "t@e.c");
			c.save();
			Files.write(upDir.resolve("f.txt"), "x".getBytes());
			upstream.add().addFilepattern("f.txt").call();
			upstream.commit().setMessage("one").setSign(false).call();
		}

		try (Git client = Git.init().setDirectory(clientDir.toFile()).setInitialBranch("main").call()) {
			StoredConfig c = client.getRepository().getConfig();
			c.setString("user", null, "name", "T");
			c.setString("user", null, "email", "t@e.c");
			c.setString("remote", "origin", "url", upDir.toString());
			c.setStringList("remote", "origin", "fetch", List.of("+refs/heads/*:refs/remotes/origin/*"));
			c.save();
			client.fetch().setRemote("origin").call();
		}

		Repository repo = new FileRepositoryBuilder()
				.setGitDir(clientDir.resolve(".git").toFile()).setMustExist(true).build();

		// Отключаем так же, как это делает плагин.
		StoredConfig config = repo.getConfig();
		config.unset("remote", "origin", "fetch");
		config.setBoolean("remote", "origin", "skipDefaultUpdate", true);
		config.setBoolean("remote", "origin", "disabled", true);
		config.save();
		repo.getRefDatabase().refresh();

		System.out.println("Ветки до попытки fetch: "
				+ repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/").size());

		// Вариант 1: как обычно делает код, setRemote без refspec.
		probe("setRemote(origin) без refspecs", () -> {
			try (Git git = new Git(repo)) {
				git.fetch().setRemote("origin").call();
			}
			return "ok";
		});

		// Вариант 2: как делает EGit FetchOperation — пустой список refspecs.
		probe("setRemote(origin) + пустой список refspecs", () -> {
			try (Git git = new Git(repo)) {
				git.fetch().setRemote("origin").setRefSpecs(List.of()).call();
			}
			return "ok";
		});

		// Вариант 3: fetch без указания remote.
		probe("fetch() без remote", () -> {
			try (Git git = new Git(repo)) {
				git.fetch().call();
			}
			return "ok";
		});

		System.out.println("Ветки после попыток: "
				+ repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/").size());
		repo.close();
	}

	private interface Call {
		String run() throws Exception;
	}

	private static void probe(String name, Call call) {
		try {
			String r = call.run();
			System.out.println("  OK    " + name + " -> " + r);
		} catch (Throwable e) {
			String msg = e.getMessage();
			if (msg != null && msg.length() > 120) {
				msg = msg.substring(0, 120) + "...";
			}
			System.out.println("  ERROR " + name + " -> " + e.getClass().getSimpleName() + ": " + msg);
		}
	}
}
