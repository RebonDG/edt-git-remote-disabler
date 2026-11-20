package ru.edt.gitremotedisabler.tests;

import java.io.File;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import ru.edt.gitremotedisabler.RemoteToggleService;

/**
 * Живой прогон плагина на указанном репозитории: отключает remote, печатает
 * состояние, включает обратно и печатает состояние снова. Используется на
 * копии настоящего рабочего репозитория.
 *
 * Аргументы: путь к репозиторию и имя remote.
 */
public final class LiveRepositoryRun {

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Использование: LiveRepositoryRun <путь> <remote>");
			System.exit(2);
		}
		File dir = new File(args[0]);
		String remote = args[1];

		Repository repo = new FileRepositoryBuilder()
				.setGitDir(new File(dir, ".git"))
				.setMustExist(true)
				.build();
		try {
			System.out.println("--- ДО ---");
			dump(repo, remote);

			System.out.println();
			System.out.println(">>> Отключаю " + remote);
			int removed = RemoteToggleService.disable(repo, remote);
			System.out.println("    удалено веток: " + removed);
			System.out.println();
			System.out.println("--- ПОСЛЕ ОТКЛЮЧЕНИЯ ---");
			dump(repo, remote);

			System.out.println();
			System.out.println(">>> Включаю " + remote);
			boolean changed = RemoteToggleService.enable(repo, remote);
			System.out.println("    изменена конфигурация: " + changed);
			System.out.println();
			System.out.println("--- ПОСЛЕ ВКЛЮЧЕНИЯ ---");
			dump(repo, remote);
		} finally {
			repo.close();
		}
	}

	private static void dump(Repository repo, String remote) throws Exception {
		repo.getRefDatabase().refresh();
		StoredConfig config = repo.getConfig();
		config.load();

		System.out.println("  URL:            " + RemoteToggleService.getUrl(repo, remote));
		System.out.println("  секция remote:  " + config.getSubsections("remote").contains(remote));
		System.out.println("  fetch refspecs: "
				+ java.util.Arrays.toString(config.getStringList("remote", remote, "fetch")));
		System.out.println("  isDisabled:     " + RemoteToggleService.isDisabled(repo, remote));
		System.out.println("  skipDefaultUpd: " + config.getBoolean("remote", remote, "skipDefaultUpdate", false));

		List<String> branches = RemoteToggleService.getRemoteTrackingBranches(repo, remote);
		System.out.println("  веток " + remote + ":   " + branches.size());
		int shown = 0;
		for (String b : branches) {
			if (shown++ >= 4) {
				System.out.println("    ... и ещё " + (branches.size() - 4));
				break;
			}
			System.out.println("    " + b);
		}

		int originBranches = 0;
		for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/")) {
			if (!ref.isSymbolic()) {
				originBranches++;
			}
		}
		System.out.println("  веток origin:   " + originBranches + " (должны остаться нетронутыми)");
	}
}
