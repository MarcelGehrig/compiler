package ch.ntb.inf.deep.config;

import ch.ntb.inf.deep.host.StdStreams;
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

	public HString getLibPaths() {
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
			StdStreams.vrb.print("  ");
		}
		StdStreams.vrb.println("project {");
		for (int i = indentLevel+1; i > 0; i--) {
			StdStreams.vrb.print("  ");
		}
		StdStreams.vrb.print("rootclasses = ");
		HString current = rootClasses;
		while (current.next != null) {
			StdStreams.vrb.print(current.toString() + ", ");
			current = current.next;
		}
		if (current != null) {
			StdStreams.vrb.println(current.toString() + ";");
		}
		
		for (int i = indentLevel+1; i > 0; i--) {
			StdStreams.vrb.print("  ");
		}
		StdStreams.vrb.println("libpath = " + libPath.toString());
		
		for (int i = indentLevel+1; i > 0; i--) {
			StdStreams.vrb.print("  ");
		}
		StdStreams.vrb.println("debuglevel = " + debugLevel);
		
		for (int i = indentLevel+1; i > 0; i--) {
			StdStreams.vrb.print("  ");
		}
		StdStreams.vrb.println("printlevel = " + printLevel);
		
		for (int i = indentLevel; i > 0; i--) {
			StdStreams.vrb.print("  ");
		}
		StdStreams.out.println("}");
	}
}
