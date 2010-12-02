package ch.ntb.inf.deep.launcher;

import java.io.IOException;

import ch.ntb.inf.deep.cfg.CFG;
import ch.ntb.inf.deep.cgPPC.MachineCode;
import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.classItems.ICclassFileConsts;
import ch.ntb.inf.deep.classItems.Method;
import ch.ntb.inf.deep.classItems.Type;
import ch.ntb.inf.deep.config.Configuration;
import ch.ntb.inf.deep.linkerPPC.Linker;
import ch.ntb.inf.deep.ssa.SSA;

public class Launcher implements ICclassFileConsts {

	public static void buildAll(String projectConfigFile, String targetConfiguration) {

		int attributes = (1 << atxCode) | (1 << atxLocalVariableTable) | (1 << atxExceptions)| (1 << atxLineNumberTable);
		
		// 1) Read configuration
		Configuration.parseAndCreateConfig(projectConfigFile, targetConfiguration);

		try {
			// 2) Read requiered classes
			Class.buildSystem(Configuration.getRootClassNames(), Configuration.getSearchPaths(), Configuration.getSystemPrimitives(), attributes);
			
			// 3) Loop One
			Class clazz = Type.classList;
			Method method;
			while(clazz != null) {
				// 3.1) Linker: calculate offsets
				Linker.calculateOffsets(clazz);
				
				method = (Method)clazz.methods;
				while(method != null) {
					// 3.2) Create CFG
					method.cfg = new CFG(method);
					
					// 3.3) Create SSA
					method.ssa = new SSA(method.cfg);
					
					// 3.4) Create machine code
					method.machineCode = new MachineCode(method.ssa);
					
					method = (Method)method.next;
				}
				
				// 3.5) Linker: calculate required size
				Linker.calculateRequiredSize(clazz);
				
				clazz = (Class)clazz.next;
			}
			
			// 4) Linker: freeze memory map
			Linker.freezeMemoryMap();
			
			// 5) Loop Two
			clazz = Type.classList;
			while(clazz != null) {
				// 5.1) Linker: calculate absolute addresses
				Linker.calculateAbsoluteAddresses(clazz);
				
				method = (Method)clazz.methods;
				while(method != null) {
					// 5.2) Code generator: fix up
					
					
					method = (Method)method.next;
				}
				
				// 5.3) Linker: Create constant block
				Linker.createConstantBlock(clazz);
				
				clazz = (Class)clazz.next;
			}
			
			// 6) Linker: Create system table
			Linker.createSystemTable();
			
			// 7) Linker: Create target image
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void downloadTargetImage() {
		// 8a) download image to target
	}

	public static void saveTargetImage2File(String file) {
		// 8b) save target image to a file
	}
}
