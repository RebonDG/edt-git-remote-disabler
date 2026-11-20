/**
 * Логика включения/отключения удалённого репозитория в локальном git-репозитории.
 *
 * Отключение не удаляет удалённый репозиторий: секция remote.<name> остаётся в
 * .git/config (url сохраняется), поэтому узел виден в дереве EGit и его можно
 * включить обратно. Меняется только следующее:
 *
 *  - refspec-и remote.<name>.fetch убираются (git перестаёт тянуть ветки),
 *    исходные значения сохраняются в remote.<name>.disabledfetch;
 *  - ставится маркер remote.<name>.disabled = true;
 *  - ставится remote.<name>.skipDefaultUpdate = true, чтобы git fetch --all и
 *    git remote update пропускали этот remote целиком;
 *  - удаляются уже скачанные ветки refs/remotes/<name>/* (аналог
 *    git branch -rd для каждой).
 *
 * Включение возвращает refspec-и, снимает маркеры и восстанавливает
 * skipDefaultUpdate в исходное состояние. Ветки появятся при следующем fetch.
 */
package ru.edt.gitremotedisabler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;

/**
 * Сервис переключения состояния удалённого репозитория. Не зависит от UI и
 * Eclipse workbench — только JGit, поэтому проверяется обычным JVM-тестом.
 */
public final class RemoteToggleService {

	private static final String SECTION = "remote"; //$NON-NLS-1$
	private static final String KEY_FETCH = "fetch"; //$NON-NLS-1$
	private static final String KEY_URL = "url"; //$NON-NLS-1$

	/** Маркер «отключён плагином». */
	private static final String KEY_DISABLED = "disabled"; //$NON-NLS-1$
	/** Сохранённые refspec-и на время отключения. */
	private static final String KEY_BACKUP_FETCH = "disabledfetch"; //$NON-NLS-1$
	/** Исходное значение skipDefaultUpdate на время отключения. */
	private static final String KEY_BACKUP_SKIP = "disabledskipdefaultupdate"; //$NON-NLS-1$
	private static final String KEY_SKIP_DEFAULT_UPDATE = "skipDefaultUpdate"; //$NON-NLS-1$

	private RemoteToggleService() {
	}

	/**
	 * Конфигурация репозитория, перечитанная с диска, если файл изменился
	 * извне (например, пользователь правил .git/config вручную или через
	 * git config).
	 */
	private static StoredConfig configOf(Repository repository) {
		StoredConfig config = repository.getConfig();
		if (config instanceof FileBasedConfig) {
			FileBasedConfig fileConfig = (FileBasedConfig) config;
			if (fileConfig.isOutdated()) {
				try {
					fileConfig.load();
				} catch (IOException | ConfigInvalidException e) {
					// Читаем то, что уже загружено: лучше показать устаревшее
					// состояние, чем упасть.
					Activator.log(e);
				}
			}
		}
		return config;
	}

	/**
	 * Удалённый репозиторий считается отключённым, если у него нет ни одного
	 * refspec-а fetch (это же состояние даёт ручной {@code git config --unset-all
	 * remote.X.fetch}) либо стоит маркер плагина.
	 */
	public static boolean isDisabled(Repository repository, String remoteName) {
		Config config = configOf(repository);
		if (config.getBoolean(SECTION, remoteName, KEY_DISABLED, false)) {
			return true;
		}
		return config.getStringList(SECTION, remoteName, KEY_FETCH).length == 0;
	}

	/**
	 * Имена скачанных веток удалённого репозитория — те, что будут удалены при
	 * отключении.
	 */
	public static List<String> getRemoteTrackingBranches(Repository repository, String remoteName)
			throws IOException {
		String prefix = Constants.R_REMOTES + remoteName + "/";
		List<String> names = new ArrayList<>();
		for (Ref ref : repository.getRefDatabase().getRefsByPrefix(prefix)) {
			// Символическая ссылка вида refs/remotes/<name>/HEAD веткой не считается.
			if (!ref.isSymbolic()) {
				names.add(ref.getName().substring(Constants.R_REMOTES.length()));
			}
		}
		Collections.sort(names);
		return names;
	}

	/** URL удалённого репозитория (первый, если их несколько). */
	public static String getUrl(Repository repository, String remoteName) {
		String[] urls = configOf(repository).getStringList(SECTION, remoteName, KEY_URL);
		return urls.length > 0 ? urls[0] : ""; //$NON-NLS-1$
	}

	/**
	 * Отключает удалённый репозиторий.
	 *
	 * @return количество удалённых remote-tracking веток
	 */
	public static int disable(Repository repository, String remoteName) throws IOException {
		StoredConfig config = configOf(repository);
		boolean alreadyDisabled = config.getBoolean(SECTION, remoteName, KEY_DISABLED, false);
		String[] fetchSpecs = config.getStringList(SECTION, remoteName, KEY_FETCH);

		if (alreadyDisabled && fetchSpecs.length == 0) {
			// Конфигурация уже в нужном состоянии — остаётся добить ветки.
			return deleteRemoteTrackingRefs(repository, remoteName);
		}

		if (fetchSpecs.length > 0) {
			config.setStringList(SECTION, remoteName, KEY_BACKUP_FETCH,
					new ArrayList<>(Arrays.asList(fetchSpecs)));
			config.unset(SECTION, remoteName, KEY_FETCH);
		}

		if (!alreadyDisabled) {
			boolean skipDefaultUpdate = config.getBoolean(SECTION, remoteName,
					KEY_SKIP_DEFAULT_UPDATE, false);
			config.setBoolean(SECTION, remoteName, KEY_BACKUP_SKIP, skipDefaultUpdate);
			config.setBoolean(SECTION, remoteName, KEY_SKIP_DEFAULT_UPDATE, true);
			config.setBoolean(SECTION, remoteName, KEY_DISABLED, true);
		}

		config.save();
		return deleteRemoteTrackingRefs(repository, remoteName);
	}

	/**
	 * Включает удалённый репозиторий обратно.
	 *
	 * @return true, если конфигурация изменилась
	 */
	public static boolean enable(Repository repository, String remoteName) throws IOException {
		StoredConfig config = configOf(repository);
		String[] fetchSpecs = config.getStringList(SECTION, remoteName, KEY_FETCH);
		boolean disabled = config.getBoolean(SECTION, remoteName, KEY_DISABLED, false);
		if (!disabled && fetchSpecs.length > 0) {
			return false;
		}

		String[] backup = config.getStringList(SECTION, remoteName, KEY_BACKUP_FETCH);
		List<String> restore = backup.length > 0
				? new ArrayList<>(Arrays.asList(backup))
				: new ArrayList<>(Collections.singletonList(
						"+" + Constants.R_HEADS + "*:" + Constants.R_REMOTES + remoteName + "/*")); //$NON-NLS-1$ //$NON-NLS-2$

		config.setStringList(SECTION, remoteName, KEY_FETCH, restore);

		if (config.getBoolean(SECTION, remoteName, KEY_BACKUP_SKIP, false)) {
			config.setBoolean(SECTION, remoteName, KEY_SKIP_DEFAULT_UPDATE, true);
		} else {
			config.unset(SECTION, remoteName, KEY_SKIP_DEFAULT_UPDATE);
		}

		config.unset(SECTION, remoteName, KEY_DISABLED);
		config.unset(SECTION, remoteName, KEY_BACKUP_FETCH);
		config.unset(SECTION, remoteName, KEY_BACKUP_SKIP);
		config.save();

		repository.getRefDatabase().refresh();
		return true;
	}

	/**
	 * Удаляет refs/remotes/<name>/* — то же, что
	 * {@code git branch -rd $(git branch -r | grep '^ *<name>/')}.
	 *
	 * @return количество удалённых веток
	 */
	private static int deleteRemoteTrackingRefs(Repository repository, String remoteName)
			throws IOException {
		String prefix = Constants.R_REMOTES + remoteName + "/";
		List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(prefix));
		Set<String> failures = new LinkedHashSet<>();
		int removed = 0;

		// Сначала символические ссылки (refs/remotes/<name>/HEAD), затем обычные
		// ветки: иначе ссылка может остаться указывать в пустоту.
		refs.sort((a, b) -> Boolean.compare(!a.isSymbolic(), !b.isSymbolic()));
		for (Ref ref : refs) {
			// Удаление символической ссылки ничего не говорит о числе веток,
			// поэтому считаем только обычные.
			boolean symbolic = ref.isSymbolic();
			try {
				RefUpdate update = repository.updateRef(ref.getName());
				update.setForceUpdate(true);
				RefUpdate.Result result = update.delete();
				if (!symbolic && isDeleted(result)) {
					removed++;
				}
			} catch (IOException e) {
				failures.add(ref.getName() + ": " + e.getMessage()); //$NON-NLS-1$
			}
		}
		repository.getRefDatabase().refresh();
		if (!failures.isEmpty()) {
			throw new IOException("Не удалось удалить часть веток: " //$NON-NLS-1$
					+ String.join("; ", failures)); //$NON-NLS-1$
		}
		return removed;
	}

	private static boolean isDeleted(RefUpdate.Result result) {
		switch (result) {
		case FORCED:
		case NO_CHANGE:
		case NEW:
		case FAST_FORWARD:
			return true;
		default:
			return false;
		}
	}
}
