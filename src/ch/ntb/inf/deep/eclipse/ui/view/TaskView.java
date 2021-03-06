package ch.ntb.inf.deep.eclipse.ui.view;

import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import ch.ntb.inf.deep.classItems.Field;
import ch.ntb.inf.deep.classItems.ICclassFileConsts;
import ch.ntb.inf.deep.classItems.ICdescAndTypeConsts;
import ch.ntb.inf.deep.classItems.Item;
import ch.ntb.inf.deep.classItems.RefType;
import ch.ntb.inf.deep.classItems.Type;
import ch.ntb.inf.deep.config.Board;
import ch.ntb.inf.deep.config.Configuration;
import ch.ntb.inf.deep.config.Parser;
import ch.ntb.inf.deep.eclipse.DeepPlugin;
import ch.ntb.inf.deep.eclipse.ui.model.TargetOpObject;
import ch.ntb.inf.deep.eclipse.ui.model.TaskObject;
import ch.ntb.inf.deep.eclipse.ui.view.ClassTreeView.RootElement;
import ch.ntb.inf.deep.eclipse.ui.view.ClassTreeView.TreeInput;
import ch.ntb.inf.deep.launcher.Launcher;
import ch.ntb.inf.deep.runtime.ppc32.Task;
import ch.ntb.inf.deep.strings.HString;
import ch.ntb.inf.deep.target.TargetConnection;
import ch.ntb.inf.deep.target.TargetConnectionException;
import ch.ntb.inf.deep.classItems.Class;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;

public class TaskView extends ViewPart implements ICdescAndTypeConsts, ICclassFileConsts{

	public static final String ID = "ch.ntb.inf.deep.eclipse.ui.view.TaskView";
	private TableViewer viewer;
	private IEclipsePreferences prefs;
	private Action refresh;
	
	private TaskObject[] taskObj;
	
	static final byte slotSize = 4;
	
	class ViewLabelProvider extends LabelProvider implements ITableLabelProvider {

		public String getColumnText(Object obj, int index) {
			if ((obj instanceof TaskObject)) {
				TaskObject to = (TaskObject)obj;
				if(to.name != "") {
					if(index == 0){
						return to.name.toString();
					}else if(index == 1){
						return Integer.toString(to.nofActivations);
					}else if(index == 2){
						return Integer.toString(to.period);
					}else if(index == 3){
						return Integer.toString(to.time);
					}else if (index == 4) {
						return Double.toString(to.load)+"%";
					}
				}

			}
			return "";
		}

		@Override
		public Image getColumnImage(Object arg0, int arg1) {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	
	@Override
	public void createPartControl(Composite parent) {
		prefs = InstanceScope.INSTANCE.getNode(DeepPlugin.PLUGIN_ID);
		Composite composite = new Composite(parent, SWT.NONE);
		composite.setLayout(new GridLayout(1, false));

		viewer = new TableViewer(composite, SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
		
		String[] titels = { "Classname", "nofActivations", "period(ms)","time(ms)" , "load(%)"};
		int[] bounds = { 400, 100, 100, 100, 100 };

		TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(titels[0]);
		column.getColumn().setWidth(bounds[0]);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(false);
		column.setEditingSupport(new TaskEditingSupport(viewer));
		
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(titels[1]);
		column.getColumn().setWidth(bounds[1]);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(false);
		column.setEditingSupport(new TaskEditingSupport(viewer));
		
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(titels[2]);
		column.getColumn().setWidth(bounds[2]);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(false);
		column.setEditingSupport(new TaskEditingSupport(viewer));
		
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(titels[3]);
		column.getColumn().setWidth(bounds[3]);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(false);
		column.setEditingSupport(new TaskEditingSupport(viewer));

		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(titels[4]);
		column.getColumn().setWidth(bounds[4]);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(false);
		column.setEditingSupport(new TaskEditingSupport(viewer));

		final Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		table.setLayoutData(new GridData(GridData.FILL_BOTH));
		
		
		viewer.setColumnProperties(titels);
		viewer.setContentProvider(new ArrayContentProvider());
		viewer.setLabelProvider(new ViewLabelProvider());
		getSite().setSelectionProvider(viewer);
		
		// TODO read task data here
		taskObj = new TaskObject[32];
		for (int i = 0; i < 32; i++) {
			taskObj[i] = new TaskObject();
		}
		getContent();	
		viewer.setInput(taskObj);
		viewer.refresh();
		createActions();
		contributeToActionBars();

		
	}
	
	private void getContent() {
		int index = 0;
		boolean wasFreezeAsserted = true;
		TargetConnection tc = Launcher.getTargetConnection();
		if(tc == null){
			taskObj[0].name = "target not connected";
			return;
		}
		try{
			if (!tc.isConnected()) tc.openConnection();
			wasFreezeAsserted = tc.getTargetState() == TargetConnection.stateDebug;
			if (!wasFreezeAsserted)	tc.stopTarget();
			
			Item testItem = RefType.refTypeList.getItemByName("ch/ntb/inf/deep/runtime/ppc32/Task");
			Class taskCls = (Class)testItem;
			Item tasks = taskCls.classFields.getItemByName("tasks");
		
			int taskArrayAddr;
			taskArrayAddr = tc.readWord(tasks.address);
			for (int i = 0; i < 34; i++) {
	              int taskAddr = tc.readWord(taskArrayAddr + 4 * i);
	              int tag = tc.readWord(taskAddr - 4);
	              RefType ref = RefType.refTypeList;
	              do {
	                     if (tag == ref.address) {
	                           Class cls = (Class)ref;
	                           Field f = (Field)cls.getField(HString.getRegisteredHString("nextTime"));
	                           long nextTime = (long)tc.readWord(taskAddr + f.offset) << (8 * slotSize) | (tc.readWord(taskAddr + f.offset + slotSize) & 0xffffffffL);
	                           if (nextTime != 0x8000000000000000L && nextTime != 0x7fffffffffffffffL) {
	                        	  taskObj[index].name = ref.name.toString();
                                  f = (Field)cls.getField(HString.getRegisteredHString("nofActivations"));
                                  taskObj[index].nofActivations = tc.readWord(taskAddr + f.offset);
                                  f = (Field)cls.getField(HString.getRegisteredHString("period"));
                                  taskObj[index].period = tc.readWord(taskAddr + f.offset);
                                  f = (Field)cls.getField(HString.getRegisteredHString("time"));
                                  taskObj[index].time = tc.readWord(taskAddr + f.offset);
                                  f = (Field)cls.getField(HString.getRegisteredHString("diffTime"));
                                  if(f != null) {
                                	  int diffTime = tc.readWord(taskAddr + f.offset);
                                	  taskObj[index].load = (double)diffTime / (10 * taskObj[index].period);
                                  }else {
                                	  taskObj[index].load = 123.45;
                                  }
                                  index++;
	                           }
	                           break;
	                     }
	                     ref = (RefType) ref.next;
	              } while (ref != null) ;
			}
			if (!wasFreezeAsserted)	tc.startTarget(-1);
		} catch (TargetConnectionException e) {
			// TODO Auto-generated catch block
			if (!wasFreezeAsserted)
				try {
					tc.startTarget(-1);
				} catch (TargetConnectionException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			e.printStackTrace();
		}

	}
	
	private void createActions() {
		refresh = new Action() {
			public void run() {
				getContent();
				viewer.refresh();
			}
		};
		refresh.setText("Refresh");
		ImageDescriptor img = ImageDescriptor.createFromImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_REDO));
		refresh.setImageDescriptor(img);
	}
	
	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	private void fillLocalPullDown(IMenuManager menu) {
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		refresh.setText("Refresh");
		ImageDescriptor img = ImageDescriptor.createFromImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_REDO));
		refresh.setImageDescriptor(img);
		manager.add(refresh);
	}


	@Override
	public void setFocus() {
		// TODO Auto-generated method stub
		viewer.getControl().setFocus();		
	}
	
	public class TaskEditingSupport extends EditingSupport {
		
		private final TableViewer viewer;
		
		public TaskEditingSupport(TableViewer viewer) {
			super(viewer);
			this.viewer = viewer;
		}
		
		@Override
		protected CellEditor getCellEditor(Object element) {
			return new TextCellEditor(viewer.getTable());
		}
		
		@Override
		protected boolean canEdit(Object element) {
			return false;
		}
		
		@Override
		protected Object getValue(Object element) {
			/*TargetOpObject op = (TargetOpObject)element;
			if(op.operation != 0 && op.description != "" && op.addr != 0) {
				return op.note;
			}*/
			
			return "Test";
		}

		@Override
		protected void setValue(Object arg0, Object arg1) {
			// TODO Auto-generated method stub	
			// can't set value, read only
		}

	}

}


