package ru.edt.gitremotedisabler;

import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * Активатор бандла: логирование ошибок в журнал EDT
 * (Главная меню → Справка → Журнал ошибок) и доступ к иконкам плагина.
 */
public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "ru.edt.gitremotedisabler"; //$NON-NLS-1$

	private static Activator instance;

	/**
	 * Собственный реестр иконок. Нужен потому, что imageDescriptor() обязан
	 * работать даже если активатор ещё не стартовал: меню строится лениво, и
	 * полагаться на getDefault() здесь нельзя.
	 */
	private static ImageRegistry imageRegistry;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		instance = null;
		if (imageRegistry != null) {
			imageRegistry.dispose();
			imageRegistry = null;
		}
		super.stop(context);
	}

	public static Activator getDefault() {
		return instance;
	}

	public static void log(Throwable e) {
		Activator activator = instance;
		String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
		if (activator != null) {
			activator.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
		} else {
			// Бандл ещё не активирован — пишем хотя бы в stderr, чтобы ошибка
			// не потерялась совсем.
			System.err.println("[gitremotedisabler] " + message);
		}
	}

	public static void logWarning(String message) {
		Activator activator = instance;
		if (activator != null) {
			activator.getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, message));
		}
	}

	/**
	 * Иконка из каталога icons/ бандла.
	 *
	 * Не зависит от того, стартовал ли активатор: бандл ищется напрямую через
	 * Platform.getBundle, файл — через FileLocator, поэтому метод одинаково
	 * работает и из каталога, и из jar. Дескрипторы кэшируются.
	 */
	public static ImageDescriptor imageDescriptor(String relativePath) {
		if (relativePath == null) {
			return null;
		}
		if (imageRegistry == null) {
			imageRegistry = new ImageRegistry();
		}
		ImageDescriptor cached = imageRegistry.getDescriptor(relativePath);
		if (cached != null) {
			return cached;
		}

		Bundle bundle = Platform.getBundle(PLUGIN_ID);
		if (bundle == null) {
			logWarning("Не найден бандл " + PLUGIN_ID + " при загрузке иконки " + relativePath);
			return null;
		}
		URL url = FileLocator.find(bundle, new Path(relativePath), null);
		if (url == null) {
			logWarning("Не найдена иконка " + relativePath + " в бандле " + PLUGIN_ID);
			return null;
		}
		ImageDescriptor descriptor = ImageDescriptor.createFromURL(url);
		imageRegistry.put(relativePath, descriptor);
		return descriptor;
	}
}
