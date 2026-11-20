package ru.edt.gitremotedisabler;

import org.eclipse.osgi.util.NLS;

/** Локализованные строки плагина. */
public final class Messages extends NLS {

	private static final String BUNDLE_NAME = "ru.edt.gitremotedisabler.messages"; //$NON-NLS-1$

	public static String DisableRemote_ConfirmHeader;
	public static String DisableRemote_ConfirmMessage;
	public static String DisableRemote_JobName;
	public static String DisableRemote_DoneHeader;
	public static String DisableRemote_DoneMessage;
	public static String EnableRemote_JobName;
	public static String EnableRemote_DoneHeader;
	public static String EnableRemote_DoneMessage;
	public static String EnableRemote_NothingToDo;
	public static String Common_ErrorHeader;
	public static String Common_NoSelection;
	public static String Menu_DisableLabel;
	public static String Menu_DisableTooltip;
	public static String Menu_EnableLabel;
	public static String Menu_EnableTooltip;

	static {
		NLS.initializeMessages(BUNDLE_NAME, Messages.class);
	}

	private Messages() {
	}
}
