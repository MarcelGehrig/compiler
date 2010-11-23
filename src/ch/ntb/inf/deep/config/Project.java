package ch.ntb.inf.deep.config;

import ch.ntb.inf.deep.strings.HString;

public class Project {
	private HString rootClasses;
	private HString libPath;
	private HString targetconf;
	private int debugLevel;
	private int printLevel;

	public Project() {
		debugLevel = 0;
		printLevel = 0;
	}

	public void setRootClasses(HString rootClasses) {
		this.rootClasses = rootClasses;
	}

	public void setLibPath(HString libPath) {
		this.libPath = libPath;
	}

	public void setTagetConfig(HString targetConf) {
		this.targetconf = targetConf;
	}

	public void setDebugLevel(int level) {
		debugLevel = level;
	}

	public void setPrintLevel(int level) {
		printLevel = level;
	}

	public HString getRootClasses() {
		return rootClasses;
	}

	public HString getLibPath() {
		return libPath;
	}

	public HString getTargetConfig() {
		return targetconf;
	}

	public int getDebugLevel() {
		return debugLevel;
	}

	public int getPrintLevel() {
		return printLevel;
	}

	public void println(int indentLevel) {
		for (int i = indentLevel; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.println("Project {");
		for (int i = indentLevel+1; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.print("Rootclasses: ");
		HString current = rootClasses;
		while (current.next != null) {
			System.out.print(current.toString() + ", ");
			current = current.next;
		}
		if (current != null) {
			System.out.println(current.toString() + ";");
		}
		
		for (int i = indentLevel+1; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.println("LibPath: " + libPath.toString());
		
		for (int i = indentLevel+1; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.println("Debuglevel: " + debugLevel);
		
		for (int i = indentLevel+1; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.println("Printlevel: " + printLevel);
		
		for (int i = indentLevel; i > 0; i--) {
			System.out.print("  ");
		}
		System.out.println("}");
	}
}
