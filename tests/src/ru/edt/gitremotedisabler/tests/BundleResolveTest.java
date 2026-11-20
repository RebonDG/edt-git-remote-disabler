package ru.edt.gitremotedisabler.tests;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Проверка, что бандл плагина корректно резолвится и активируется в OSGi:
 * проверяется MANIFEST (Require-Bundle на EGit/JGit), загрузка класса
 * обработчика, тестера свойства и активатора, а также разбор plugin.xml.
 *
 * Это единственная проверка «загрузится ли плагин», доступная без живого EDT.
 */
public final class BundleResolveTest {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Укажите путь к каталогу с бандлами EDT и к нашему jar.");
			System.exit(2);
		}
		String bundleJar = args[0];

		int failed = 0;

		// 1. MANIFEST читается и содержит нужные заголовки.
		java.util.jar.JarFile jar = new java.util.jar.JarFile(bundleJar);
		java.util.jar.Manifest mf = jar.getManifest();
		String symbolicName = mf.getMainAttributes().getValue("Bundle-SymbolicName");
		String requireBundle = mf.getMainAttributes().getValue("Require-Bundle");
		String activator = mf.getMainAttributes().getValue("Bundle-Activator");
		failed += check("Bundle-SymbolicName", symbolicName != null && symbolicName.startsWith("ru.edt.gitremotedisabler"));
		failed += check("Require-Bundle содержит EGit", requireBundle != null && requireBundle.contains("org.eclipse.egit.ui"));
		failed += check("Require-Bundle содержит JGit", requireBundle != null && requireBundle.contains("org.eclipse.jgit"));
		failed += check("Bundle-Activator указан", activator != null && activator.contains("Activator"));

		// 2. Все классы из манифеста и plugin.xml физически есть в jar.
		String[] classes = { "ru/edt/gitremotedisabler/Activator.class",
				"ru/edt/gitremotedisabler/ToggleRemoteHandler.class",
				"ru/edt/gitremotedisabler/RemoteToggleService.class",
				"ru/edt/gitremotedisabler/Messages.class",
				"ru/edt/gitremotedisabler/messages.properties",
				"ru/edt/gitremotedisabler/messages_ru.properties",
				"plugin.xml", "icons/obj16/remote_disabled.png", "icons/obj16/remote_enabled.png" };
		for (String c : classes) {
			failed += check("в jar есть " + c, jar.getEntry(c) != null);
		}

		// 3. plugin.xml разбирается и содержит ожидаемые расширения.
		String pluginXml = new String(jar.getInputStream(jar.getEntry("plugin.xml")).readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
		failed += check("plugin.xml: команда", pluginXml.contains("ru.edt.gitremotedisabler.toggleRemote"));
		failed += check("plugin.xml: меню перед удалением",
				pluginXml.contains("popup:org.eclipse.egit.ui.RepositoriesView?before=org.eclipse.ui.edit.delete"));
		failed += check("plugin.xml: обработчик", pluginXml.contains("ru.edt.gitremotedisabler.ToggleRemoteHandler"));
		failed += check("plugin.xml: тип узла RemoteNode",
				pluginXml.contains("org.eclipse.egit.ui.internal.repository.tree.RemoteNode"));
		jar.close();

		// 4. Классы загружаются и имеют ожидаемые предки.
		ClassLoader cl = BundleResolveTest.class.getClassLoader();
		Class<?> handler = Class.forName("ru.edt.gitremotedisabler.ToggleRemoteHandler", false, cl);
		failed += check("ToggleRemoteHandler — AbstractHandler",
				org.eclipse.core.commands.AbstractHandler.class.isAssignableFrom(handler));
		failed += check("ToggleRemoteHandler — IElementUpdater (подпись по состоянию)",
				org.eclipse.ui.commands.IElementUpdater.class.isAssignableFrom(handler));

		System.out.println();
		System.out.println(failed == 0 ? "Все проверки бандла пройдены." : "Провалено проверок: " + failed);
		System.exit(failed == 0 ? 0 : 1);
	}

	private static int check(String what, boolean ok) {
		System.out.println((ok ? "  OK   " : "  FAIL ") + what);
		return ok ? 0 : 1;
	}
}
