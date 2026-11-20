/**
 * Обработчик команды «Отключить/Включить удалённый репозиторий» в дереве EGit.
 *
 * Работает с выделенным узлом удалённого репозитория (RemoteNode) в
 * представлении «Git Repositories» (org.eclipse.egit.ui.RepositoriesView).
 * Действие выполняется в фоновом задании, после чего дерево перестраивается.
 *
 * В меню показывается ровно один пункт. Его текст и иконка выставляются в
 * {@link #updateElement} по текущему состоянию remote, поэтому пользователь
 * видит «Отключить…» у включённого репозитория и «Включить…» у отключённого.
 * Какое действие выполнить, execute определяет сам по состоянию, а не по
 * нажатому варианту — так двойной щелчок или устаревшее меню не приводят к
 * неверной операции.
 */
package ru.edt.gitremotedisabler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.ui.internal.repository.RepositoriesView;
import org.eclipse.egit.ui.internal.repository.tree.RepositoryTreeNode;
import org.eclipse.egit.ui.internal.repository.tree.RepositoryTreeNodeType;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

@SuppressWarnings("restriction")
public class ToggleRemoteHandler extends AbstractHandler implements IElementUpdater {

	private static final String ICON_DISABLE = "icons/obj16/remote_disabled.png"; //$NON-NLS-1$
	private static final String ICON_ENABLE = "icons/obj16/remote_enabled.png"; //$NON-NLS-1$

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Shell shell = HandlerUtil.getActiveShell(event);
		RepositoryTreeNode<?> node = getSelectedRemoteNode(HandlerUtil.getCurrentSelection(event));
		if (node == null) {
			MessageDialog.openWarning(shell, Messages.Common_ErrorHeader,
					Messages.Common_NoSelection);
			return null;
		}

		Repository repository = node.getRepository();
		String remoteName = String.valueOf(node.getObject());

		boolean disabled;
		int branches;
		try {
			disabled = RemoteToggleService.isDisabled(repository, remoteName);
			branches = RemoteToggleService.getRemoteTrackingBranches(repository, remoteName).size();
		} catch (IOException e) {
			Activator.log(e);
			MessageDialog.openError(shell, Messages.Common_ErrorHeader, describe(e));
			return null;
		}

		if (disabled) {
			startEnable(shell, repository, remoteName);
		} else {
			startDisable(shell, repository, remoteName, branches);
		}
		return null;
	}

	/**
	 * Подпись и иконка пункта меню под текущее состояние выделенного remote.
	 * Вызывается Eclipse при построении меню.
	 */
	@Override
	public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
		RepositoryTreeNode<?> node = getSelectedRemoteNode(getSelection(element));
		if (node == null) {
			return;
		}
		String remoteName = String.valueOf(node.getObject());
		boolean disabled;
		try {
			disabled = RemoteToggleService.isDisabled(node.getRepository(), remoteName);
		} catch (RuntimeException e) {
			// Меню не должно падать из-за недоступного .git/config.
			Activator.log(e);
			return;
		}

		if (disabled) {
			element.setText(Messages.Menu_EnableLabel);
			element.setTooltip(Messages.Menu_EnableTooltip.replace("{0}", remoteName)); //$NON-NLS-1$
			setIcon(element, ICON_ENABLE);
		} else {
			element.setText(Messages.Menu_DisableLabel);
			element.setTooltip(Messages.Menu_DisableTooltip.replace("{0}", remoteName)); //$NON-NLS-1$
			setIcon(element, ICON_DISABLE);
		}
	}

	private static void setIcon(UIElement element, String path) {
		ImageDescriptor descriptor = Activator.imageDescriptor(path);
		if (descriptor != null) {
			element.setIcon(descriptor);
		}
	}

	private static ISelection getSelection(UIElement element) {
		if (element == null) {
			return null;
		}
		ISelectionService service = element.getServiceLocator() != null
				? element.getServiceLocator().getService(ISelectionService.class)
				: null;
		return service != null ? service.getSelection() : null;
	}

	private void startDisable(Shell shell, Repository repository, String remoteName, int branches) {
		String message = Messages.DisableRemote_ConfirmMessage
				.replace("{0}", remoteName) //$NON-NLS-1$
				.replace("{1}", Integer.toString(branches)); //$NON-NLS-1$
		if (!MessageDialog.openConfirm(shell, Messages.DisableRemote_ConfirmHeader, message)) {
			return;
		}

		schedule(new Job(Messages.DisableRemote_JobName) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					int removed = RemoteToggleService.disable(repository, remoteName);
					String done = Messages.DisableRemote_DoneMessage
							.replace("{0}", remoteName) //$NON-NLS-1$
							.replace("{1}", Integer.toString(removed)); //$NON-NLS-1$
					openInfo(shell, Messages.DisableRemote_DoneHeader, done);
				} catch (IOException e) {
					Activator.log(e);
					openError(shell, describe(e));
					return Status.CANCEL_STATUS;
				}
				return Status.OK_STATUS;
			}
		});
	}

	private void startEnable(Shell shell, Repository repository, String remoteName) {
		schedule(new Job(Messages.EnableRemote_JobName) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					boolean changed = RemoteToggleService.enable(repository, remoteName);
					String done = changed
							? Messages.EnableRemote_DoneMessage.replace("{0}", remoteName) //$NON-NLS-1$
							: Messages.EnableRemote_NothingToDo.replace("{0}", remoteName); //$NON-NLS-1$
					openInfo(shell, Messages.EnableRemote_DoneHeader, done);
				} catch (IOException e) {
					Activator.log(e);
					openError(shell, describe(e));
					return Status.CANCEL_STATUS;
				}
				return Status.OK_STATUS;
			}
		});
	}

	private static void schedule(Job job) {
		job.setUser(true);
		job.setPriority(Job.SHORT);
		job.schedule();
	}

	private static void openInfo(Shell shell, String header, String text) {
		runInUi(shell, () -> MessageDialog.openInformation(shell, header, text));
	}

	private static void openError(Shell shell, String text) {
		runInUi(shell, () -> MessageDialog.openError(shell, Messages.Common_ErrorHeader, text));
	}

	private static void runInUi(Shell shell, Runnable action) {
		Display display = shell != null ? shell.getDisplay() : Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			try {
				action.run();
			} finally {
				refreshRepositoriesViews();
			}
		});
	}

	/** Перестраивает все открытые представления репозиториев. */
	static void refreshRepositoriesViews() {
		try {
			for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
				for (IWorkbenchPage page : window.getPages()) {
					IViewPart view = page.findView(RepositoriesView.VIEW_ID);
					if (view instanceof RepositoriesView) {
						((RepositoriesView) view).refresh();
					}
				}
			}
		} catch (IllegalStateException e) {
			// Workbench закрывается — обновлять нечего.
		}
	}

	private static RepositoryTreeNode<?> getSelectedRemoteNode(ISelection selection) {
		if (!(selection instanceof IStructuredSelection)) {
			return null;
		}
		List<?> elements = new ArrayList<>(((IStructuredSelection) selection).toList());
		if (elements.size() != 1) {
			return null;
		}
		Object element = elements.get(0);
		if (!(element instanceof RepositoryTreeNode)) {
			return null;
		}
		RepositoryTreeNode<?> node = (RepositoryTreeNode<?>) element;
		return node.getType() == RepositoryTreeNodeType.REMOTE ? node : null;
	}

	private static String describe(Throwable e) {
		String message = e.getMessage();
		return message != null && !message.isEmpty() ? message : e.getClass().getName();
	}
}
