package ch.ntb.inf.deep.linkerPPC;

import java.io.PrintStream;
import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.classItems.Constant;
import ch.ntb.inf.deep.classItems.StdConstant;
import ch.ntb.inf.deep.classItems.ICclassFileConsts;
import ch.ntb.inf.deep.classItems.ICdescAndTypeConsts;
import ch.ntb.inf.deep.classItems.Item;
import ch.ntb.inf.deep.classItems.Method;
import ch.ntb.inf.deep.classItems.StringLiteral;
import ch.ntb.inf.deep.classItems.Type;
import ch.ntb.inf.deep.config.Configuration;
import ch.ntb.inf.deep.config.IAttributes;
import ch.ntb.inf.deep.config.Segment;
import ch.ntb.inf.deep.config.Device;
import ch.ntb.inf.deep.host.ErrorReporter;
import ch.ntb.inf.deep.strings.HString;

public class Linker implements ICclassFileConsts, ICdescAndTypeConsts, IAttributes {
	static final byte slotSize = 4; // 4 bytes
	static{
		assert (slotSize & (slotSize-1)) == 0; // assert:  slotSize == power of 2
	}

	protected static final boolean dbg = true; // enable/disable debugging outputs for the linker
	
	public static int sizeInByte = 0;
	public static int sLength = 0;

	public static TargetMemorySegment targetImage;
	private static TargetMemorySegment lastTargetMemorySegment;

	private static final int stringHeaderSize = 4; // byte
	private static final ErrorReporter reporter = ErrorReporter.reporter;

	private static PrintStream vrb = System.out;
		
	private static int[] systemTable;
	private static int systemTableSize;

	public static void calculateOffsets(Class clazz) {
		int c1 = 0,	// index for: constant, float or double
			c2 = 0,	// index for: constant, string literal
			c3 = 0,	// offset for: class field, not constant
			c4 = 0,	// index for: instance field
			c5 = 0,	// counter for: class field, reference (global pointers)
			c6 = 0; // index for: instance method
		
		if(dbg) vrb.println("[LINKER] START: Calculating offsets and indexes for class \"" + clazz.name +"\":\n");
			
		if(dbg) vrb.println("  1) Calculating indexes and offsets for the constant- and string pool:");
		if(clazz.constPool != null && clazz.constPool.length > 0) {
			Item cpe;
			int size = 0;
			for(int i = 0; i < clazz.constPool.length; i++) {
				cpe = clazz.constPool[i];
				if(cpe instanceof Constant) { // constant field or constant value or string literal
					if(cpe instanceof StdConstant && (cpe.type == Type.wellKnownTypes[txFloat] || cpe.type == Type.wellKnownTypes[txDouble])) { // constant float or double value -> constant pool
						size = ((Type)cpe.type).sizeInBits / 8; // size in bits
						c1 = getCorrectOffset(c1, size);
						cpe.index = c1; // save index
						c1 += size; // prepare counter for next round
					}
					else if(cpe instanceof StringLiteral) { // string literal -> string pool
						size = roundUpToNextWord(((StringLiteral)cpe).string.sizeInByte()); // use the size of the string not of the reference!
						cpe.index = c2;// save index
						c2 += stringHeaderSize + size; // prepare counter for next round
					}
				}
				if(dbg) { // Output if debugging is enabled
					vrb.println("     > Entry: " + cpe.name);
					if(cpe.type != null) vrb.println("       Type: " + cpe.type.name); else vrb.println("       Type: unkown");
					vrb.println("       Index: 0x" + Integer.toHexString(cpe.index));
					vrb.println("       Offset: 0x" + Integer.toHexString(cpe.offset));
				}
			}
			clazz.constantPoolSize = roundUpToNextWord(c1); // set the size of the constant pool of this class
			clazz.stringPoolSize = roundUpToNextWord(c2); // set the size of the constant pool of this class
		}		
		
		// Calculate the total size of all instance fields of all base classes
		if(dbg) vrb.println("  2) Calculating the total size of all instance fields of all base classes:");
		int bcifs = 0;
		Class bc = (Class)clazz.type;
		while(bc != null) {
			bcifs += bc.instanceFieldsSize;
			bc = (Class)bc.type;
		}
		if(dbg) vrb.println("     " + bcifs + " byte (0x" + Integer.toHexString(bcifs) + ")");
		
		// Fields
		if(dbg) vrb.println("  3) Calculating indexes and offsets for all fields:");
		if(clazz.nOfClassFields > 0 || clazz.nOfInstanceFields > 0) {
			Item field = clazz.fields;
			int size;
			while(field != null) {
				
				size = ((Type)field.type).sizeInBits; // size in bits
				if(size < 8) size = 1; // use one byte even if the size is smaller (e.g. for a boolean)
				else size /= 8; // convert from bits to bytes
				
				if((field.accAndPropFlags & (1 << apfStatic)) != 0) { // class field					
					if((field.accAndPropFlags & (1 << dpfConst)) == 0) { // non constant field
						c3 = getCorrectOffset(c3, size);
						field.offset = c3;// save offset
						c3 += size; // prepare counter for next round
					}
					if(((Type)field.type).category == tcRef) c5++; // count references (global pointers)
				}
				else { // instance field
					c4 = getCorrectOffset(c4, size);
					field.index = c4 + bcifs; // save index
					c4 += size; // prepare counter for next round
				}
				if(dbg) { // Output if debugging is enabled
					vrb.println("     > Field: " + field.name);
					vrb.println("       Type: " + field.type.name);
					vrb.println("       AccAndPropFlags: 0x" + Integer.toHexString(field.accAndPropFlags));
					if((field.accAndPropFlags & (1 << apfStatic)) != 0) vrb.println("       Static: yes"); else vrb.println("       Static: no");
					if((field.accAndPropFlags & (1 << dpfConst)) != 0) vrb.println("       Constant: yes"); else vrb.println("       Constant: no");
					vrb.println("       Index: 0x" + Integer.toHexString(field.index));
					vrb.println("       Offset: 0x" + Integer.toHexString(field.offset));
				}
				
				field = field.next;
			}
			clazz.classFieldsSize = roundUpToNextWord(c3); // set the size of all non constant class fields
			clazz.instanceFieldsSize = roundUpToNextWord(c4); // set the size of all instance fields 
			clazz.nOfReferences = c5; // set the number of static fields which are references (number of global pointers)
		}
		else {
			if(dbg) vrb.println("     <none>");
		}
		
		// Instance methods (for instance methods, index is the byte offset of a method in the class descriptor starting at the size entry)
		if(dbg) vrb.println("  3) Methods:");
		c6 = (2 + clazz.nOfInterfaces) * 4; // constant offset for all instance methods
		if(clazz.nOfMethods > 0) {
			Method method = (Method)clazz.methods;
			while(method != null) {
				if((method.accAndPropFlags & (1 << dpfSysPrimitive)) == 0 && (method.accAndPropFlags & (1 << apfStatic)) == 0) {
					method.index = c6;
					c6 += 4;
				}
				if(dbg) {
					vrb.println("     > Method: " + method.name);
					vrb.println("       AccAndPropFlags: 0x" + Integer.toHexString(method.accAndPropFlags));
					if((method.accAndPropFlags & (1 << apfStatic)) != 0) vrb.println("       Static: yes"); else vrb.println("       Static: no");
					if((method.accAndPropFlags & (1 << dpfSysPrimitive)) != 0) vrb.println("       System primitive: yes"); else vrb.println("       System primitive: no");
					vrb.println("       Index: 0x" + Integer.toHexString(method.index));
					vrb.println("       Offset: 0x" + Integer.toHexString(method.offset));
				}
				method = (Method)method.next;
			}
		}
		else {
			if(dbg) vrb.println("     <none>");
		}
		
		if(dbg) vrb.println("\n[LINKER] END: calculating offsets and indexes for class \"" + clazz.name +"\"\n");
	}
	
	public static void calculateRequiredSize(Class clazz) {
		
		if(dbg) vrb.println("[LINKER] START: Calculating required size for class \"" + clazz.name +"\":\n");
		
		// machine code size
		if(dbg) vrb.print("  1) Code:");
		Method m = (Method)clazz.methods;
		int codeSize = 0; // machine code size for the hole class
		while(m != null) {
			if(m.machineCode != null) {
				if(m.offset < 0) { // offset not given by configuration
					m.offset = codeSize;
					codeSize += m.machineCode.iCount * 4; // iCount = number of instructions!
				}
				else { // offset given by configuration
					if(codeSize < m.machineCode.iCount * 4 + m.offset) codeSize = m.offset + m.machineCode.iCount * 4;
				}
				if(dbg) vrb.println("    > " + m.name + ": codeSize = " + m.machineCode.iCount * 4 + " byte");
			}
			m = (Method)m.next;
		}
		clazz.machineCodeSize = codeSize;
		if(dbg) vrb.println("    Total code size: " + codeSize + " byte");
		
		// size of class fields --> already set while calculating offsets
		
		if(dbg) vrb.print("  2) Constant block: ");
		// constant block size
		clazz.classDescriptorSize = (clazz.nOfInstanceMethods + clazz.nOfInterfaces + clazz.nOfBaseClasses + 3) * 4;
		clazz.constantBlockSize = 4 // constBlockSize field
								+ 4 // codeBase field
								+ 4 // codeSize field
								+ 4 // varBase field
								+ 4 // varSize field
								+ 4 // clinitAddr field
								+ 4 // nofPtrs field
								+ 4 * clazz.nOfReferences
								+ clazz.classDescriptorSize
								+ clazz.constantPoolSize // size of the constant pool already set while calculating offsets
								+ clazz.stringPoolSize // size of the string pool already set while calculating offsets
								+ 4; // Checksum field
		
		if(dbg) vrb.println(clazz.constantBlockSize + " byte");
		
		if(dbg) vrb.println("\n[LINKER] END: Calculating required size for class \"" + clazz.name +"\"\n");
	}
	
	public static void freezeMemoryMap() {
		systemTableSize = 8 + 2 * Configuration.getNumberOfStacks() + 2 * Configuration.getNumberOfHeaps() + Type.nofClasses; // TODO @Martin this should'nt be here! Move it to a better place...
		
		// 1) Set a segment for the code, the static fields and the constant block for each class
		Item item = Type.classList;
		Segment s;
		while(item != null) {
			// Code
			if( item instanceof Class){
				Class c = (Class)item;
				s = Configuration.getCodeSegmentOf(c.name);
				if(s == null) reporter.error(731, "Can't get a memory segment for the code of class " + c.name + "!\n");
				else {
					if(s.subSegments != null) s = getFirstFittingSegment(s.subSegments, atrCode, c.machineCodeSize);
					c.codeOffset = s.getRequiredSize();
					if(c.machineCodeSize > 0) s.addToRequiredSize(c.machineCodeSize);
					c.codeSegment = s;
				}
				
				// Var
				s = Configuration.getVarSegmentOf(c.name);
				if(s == null) reporter.error(731, "Can't get a memory segment for the static variables of class " + c.name + "!\n");
				else {
					if(s.subSegments != null) s = getFirstFittingSegment(s, atrVar, c.classFieldsSize);
					c.varOffset = s.getRequiredSize();
					if(c.classFieldsSize > 0) s.addToRequiredSize(c.classFieldsSize);
					c.varSegment = s;
				}
				
				// Const
				s = Configuration.getConstSegmentOf(c.name);
				if(s == null) reporter.error(731, "Can't get a memory segment for the constant block of class " + c.name + "!\n");
				else {
					if(s.subSegments != null) s = getFirstFittingSegment(s, atrConst, c.constantBlockSize);
					c.constOffset = s.getRequiredSize();
					if(c.constantBlockSize > 0) s.addToRequiredSize(c.constantBlockSize);
					c.constSegment = s;
					
				}		
			}
			item = item.next;
		}

		Segment[] sysTabs = Configuration.getSysTabSegments(); // TODO @Martin: implement this for more than one system table!
		if(sysTabs != null && sysTabs.length > 0) {
			for(int i = 0; i < sysTabs.length; i++) {
				sysTabs[i].addToRequiredSize(systemTableSize * 4); 
			}
		}
		else reporter.error(731, "Can't get a memory segment for the systemtable!");
	
		// 2) Check and set the size for each used segment
		Device d = Configuration.getFirstDevice();
		while(d != null) {
//			System.out.println("Device: " + d.getName() + "\n");
			if(d.lastSegment != null) setSegmentSize(d.lastSegment);
			d = d.next;
		}
		
		// 3) Set base addresses for each used segment
		d = Configuration.getFirstDevice();
		//usedSegments = new Segment[nOfUsedSegments];
		while(d != null) {
			vrb.println("Start setting base addresses for segments in device \"" + d.getName() +"\":");
			//System.out.println("Device: " + d.getName() + "\n");
			if(d.segments != null) setBaseAddress(d.segments, d.getbaseAddress());
			vrb.println("End setting base addresses for segments in device \"" + d.getName() +"\":\n");		
			d = d.next;
		}
	}
	
	public static void calculateAbsoluteAddresses(Class clazz) {
		if(dbg) vrb.println("\n[LINKER] START: Calculating absolute addresses for class \"" + clazz.name +"\":\n");
		
		int varBase = clazz.varSegment.getBaseAddress() + clazz.varOffset;
		int codeBase = clazz.codeSegment.getBaseAddress() + clazz.codeOffset;
		int stringPoolBase = clazz.constSegment.getBaseAddress() + (7 + clazz.nOfReferences) * 4 + clazz.classDescriptorSize;
		int constPoolBase = stringPoolBase + clazz.stringPoolSize;
		
		// Class/static fields
		if(clazz.nOfClassFields > 0) {
			Item field = clazz.fields;
			if(dbg) vrb.println("  Static fields:");
			while(field != null) {
				if((field.accAndPropFlags & (1 << apfStatic)) != 0) { // class field
					if((field.accAndPropFlags & (1 << dpfConst)) != 0) { // constant field
						if(field.type == Type.wellKnownTypes[txFloat] || field.type == Type.wellKnownTypes[txDouble]) { // float or double -> constant pool
							field.address = clazz.constSegment.getBaseAddress() + clazz.constOffset + 4* (7 + clazz.nOfReferences) + clazz.classDescriptorSize + clazz.stringPoolSize + field.index;
						}
						else if(field.type == Type.wellKnownTypes[txString]) { // literal string -> string pool
							field.address = clazz.constSegment.getBaseAddress() + clazz.constOffset + 4* (7 + clazz.nOfReferences) + clazz.classDescriptorSize + field.index;
						}
						else if(((Type)field.type).category == tcRef) { // reference but not literal string
							if(varBase != -1 && field.offset != -1) field.address = varBase + field.offset;
						}
					}
					else { // non constant field -> var section
						if(varBase != -1 && field.offset != -1) field.address = varBase + field.offset;
						else reporter.error(9999, "varBase of class " + clazz.name + " not set or offset of field " + field.name + " not set!");
					}
				}
				if(dbg) vrb.print("    > " + field.name + ": Offset = 0x" + Integer.toHexString(field.offset) + ", Index = 0x" + Integer.toHexString(field.index) + ", Address = 0x" + Integer.toHexString(field.address) + "\n");
				field = field.next;
			}
		}
		
		// Methods
		if(clazz.nOfMethods > 0) {
			Method method = (Method)clazz.methods;
			if(dbg) vrb.println("  Methods:");
			while(method != null) {
				if(codeBase != -1 && method.offset != -1) method.address = codeBase + method.offset;
				if(dbg) vrb.print("    > " + method.name + ": Offset = 0x" + Integer.toHexString(method.offset) + ", Index = 0x" + Integer.toHexString(method.index) + ", Address = 0x" + Integer.toHexString(method.address) + "\n");
				method = (Method)method.next;
			}
		}
		
		// Constants
		if(clazz.constPool != null && clazz.constPool.length > 0) {
			Item cpe;
			if(dbg) vrb.println("  Constant pool:");
			for(int i = 0; i < clazz.constPool.length; i++) {
				cpe = clazz.constPool[i];
				if(cpe instanceof StdConstant && (cpe.type == Type.wellKnownTypes[txFloat] || cpe.type == Type.wellKnownTypes[txDouble])) { // constant float or double value -> constant pool
					if(cpe.index != -1) cpe.address = constPoolBase + cpe.index;
					else reporter.error(9999, "Index of class pool entry #" + i + " (" + cpe.type.name + ") not set!");
				}
				else if(cpe instanceof StringLiteral) { // string literal -> string pool
					if(cpe.index != -1) cpe.address = stringPoolBase + cpe.index;
					else reporter.error(9999, "Index of class pool entry #" + i + " (" + cpe.type.name + ") not set!");
				}
				if(dbg) {
					if(cpe.type != null) vrb.print("    > #" + i + ": Type = " + cpe.type.name + ", Offset = 0x" + Integer.toHexString(cpe.offset) + ", Index = 0x" + Integer.toHexString(cpe.index) + ", Address = 0x" + Integer.toHexString(cpe.address) + "\n");
					else vrb.print("    > #" + i + ": Type = <unknown>, Offset = 0x" + Integer.toHexString(cpe.offset) + ", Index = 0x" + Integer.toHexString(cpe.index) + ", Address = 0x" + Integer.toHexString(cpe.address) + "\n");
				}
			}
		}
		
		// Class descriptor
		clazz.address = clazz.constSegment.getBaseAddress() + clazz.constOffset + 4 * (6 + clazz.nOfReferences + clazz.nOfInstanceMethods + clazz.nOfInterfaces + 2);
		
		vrb.println("\n[LINKER] END: Calculating absolute addresses for class \"" + clazz.name +"\"\n");
	}
		
	public static void createConstantBlock(Class clazz) {
		
		vrb.println("[LINKER] START: Creating constant block for class \"" + clazz.name +"\":\n");
		
		clazz.constantBlock = new int[clazz.constantBlockSize/4];
		
		vrb.println("  Constant block size: " + clazz.constantBlockSize + " byte -> " + clazz.constantBlock.length);
		vrb.println("    Constantblock header: 28 byte -> 7");
		vrb.println("    Number of references: " + clazz.nOfReferences + " (" + clazz.nOfReferences * 4 + " byte)");
		vrb.println("    Class descriptor size: " + clazz.classDescriptorSize + " byte -> " + clazz.classDescriptorSize / 4);
		vrb.println("    String pool size: " + clazz.stringPoolSize + " byte -> " + clazz.stringPoolSize / 4);
		vrb.println("    Constant pool size: " + clazz.constantPoolSize + " byte -> " + clazz.constantPoolSize / 4);
		vrb.println("  Number of instance methods: " + clazz.nOfInstanceMethods);
		vrb.println("  Number of interfaces: " + clazz.nOfInterfaces);
		
		
		// 1) Insert Header
		clazz.constantBlock[0] = clazz.constantBlockSize;								// constBlockSize
		clazz.constantBlock[1] = clazz.codeSegment.getBaseAddress() + clazz.codeOffset;	// codeBase
		clazz.constantBlock[2] = clazz.machineCodeSize;									// codeSize
		clazz.constantBlock[3] = clazz.varSegment.getBaseAddress() + clazz.varOffset;	// varBase
		clazz.constantBlock[4] = clazz.classFieldsSize;									// varSize
		Method clinit = clazz.getClassConstructor();									// clinitAddr
		if(clinit != null) {
			clazz.constantBlock[5] = clinit.address;
			//vrb.println(" ***************** <clinit> of class " + clazz.name + ": " + clinit.name);
		}
		else clazz.constantBlock[5] = -1; // the address of the class constructor is set to -1 if there is no one in this class
		
		// 2) Insert References (Pointers)
		clazz.constantBlock[6] = clazz.nOfReferences;
		if(clazz.nOfReferences > 0) {
			Item field = clazz.fields;
			int index = 0;
			while(field != null) {
				if((field.accAndPropFlags & (1 << apfStatic)) != 0 && ((Type)field.type).category == tcRef)
					clazz.constantBlock[7 + index] = field.address;
				field = field.next;
			}
		}
		
		
		// 3) Class descriptor 
		// 3a) Insert instance method addresses
		int classDescriptorOffset = 6 + clazz.nOfReferences;
		int imc = 0;
		if(clazz.nOfInstanceMethods > 0) {
			Method m = (Method)clazz.methods;
			while(m != null) {
				if((m.accAndPropFlags & (1 << dpfSysPrimitive)) == 0 && (m.accAndPropFlags & (1 << apfStatic)) == 0) {
					clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods - imc] = m.address;
					//vrb.println(" ***************** : Instance Method #" + imc + ": " + m.name);
					imc++;
				}
				m = (Method)m.next;
			}
		}
		
		// 3b) Insert interfaces
		if(clazz.nOfInterfaces > 0) {
			for(int i = 0; i < clazz.nOfInterfaces; i++) {
				assert clazz.interfaces[i] != null: "ERROR: Interface is NULL! Current Interface: " + i +"/" + clazz.nOfInterfaces;
				clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods + clazz.nOfInterfaces - i] = clazz.interfaces[i].address;
			}
		}
		
		// 3c) Insert extension level
		clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods + clazz.nOfInterfaces + 1] = clazz.nOfBaseClasses;
		
		// 3d) Insert size
		clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods + clazz.nOfInterfaces + 2] = clazz.objectSize;
		
		// 3e) Insert class name address
		clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods + clazz.nOfInterfaces + 3] = 0x12345678; // TODO set the right value here! -> address of the first entrie of the const/string pool?
		
		// 3f) Insert base classes
		if(clazz.nOfBaseClasses > 0) {
			Class bc = (Class)clazz.type;
			for(int i = 0; i < clazz.nOfBaseClasses; i++) {
				assert bc != null: "ERROR: Base class is NULL! Current base class: " + i + "/" + clazz.nOfBaseClasses;
				clazz.constantBlock[classDescriptorOffset + clazz.nOfInstanceMethods + clazz.nOfInterfaces + 3 + i] = bc.address;
				bc = (Class)bc.type;
			}
		}
		
		// 4) String pool
		int stringPoolOffset = classDescriptorOffset + clazz.classDescriptorSize / 4 + 1;
		if(clazz.constPool != null) {
			for(int i = 0; i < clazz.constPool.length; i++) {
				int index = clazz.constPool[i].index/4;
				if(clazz.constPool[i].type == Type.wellKnownTypes[txString] && (clazz.constPool[i].accAndPropFlags & (1 << dpfConst)) != 0) {
				//if(clazz.constPool[i] instanceof StringLiteral) {
					HString s = ((StringLiteral)clazz.constPool[i]).string;
					sizeInByte = s.sizeInByte();
					sLength = s.length();
					if(s.sizeInByte()/s.length() == 1) { // string is a H8String
						int c = 0, word = 0;
						clazz.constantBlock[stringPoolOffset + index++] = (s.length() << 16) + 0x0800; // add string header
						for(int j = 0; j < s.length(); j++) {
							word = (word << 8) + s.charAt(j);
							c++;
							if(c > 3 || j == s.length() - 1) {
								clazz.constantBlock[stringPoolOffset + index] = word;
								c = 0;
								word = 0;
								index++;
							}
						}
					}
					else { // string is a H16String
						assert s.sizeInByte()/s.length() == 2: "String is neighter a 8bit nor a 16bit char array!";
						int c = 0, word = 0;
						clazz.constantBlock[stringPoolOffset + index++] = (s.length() << 16) + 0x1000; // add string header
						for(int j = 0; j < s.length(); j++) {
							word = (word << 16) + s.charAt(j);
							c++;
							if(c > 1 || j == s.length() - 1) {
								clazz.constantBlock[stringPoolOffset + index] = word;
								c = 0;
								word = 0;
								index++;
							}
						}
					}	
				}
			}
		}
	
		// 5) Constant pool
		int constantPoolOffset = stringPoolOffset + clazz.stringPoolSize / 4;
//		if(clazz.constPool != null) {
		if(clazz.constantPoolSize > 0) {
			int index = 0;
			Item cpe;
			for(int i = 0; i < clazz.constPool.length; i++) {
				cpe = clazz.constPool[i];
				vrb.println(" ************ Proceeding const pool entry #" + i + " " + cpe.name + ":");
				if(cpe.type != null) vrb.println("              - Type: " + cpe.type.name); else vrb.println("              - Type: <null>");
				if((cpe.accAndPropFlags & (1 << dpfConst)) != 0) vrb.println("              - Constant: yes"); else vrb.println("              - Constant: no");
				
				index = cpe.index/4;
				if(cpe.type == Type.wellKnownTypes[Type.txFloat] && (cpe.accAndPropFlags & (1 << dpfConst)) != 0 && cpe instanceof StdConstant) { // TODO @Martin: is this correct???
					vrb.println(" ************ Inserting Float into CP: " + cpe.name);
					clazz.constantBlock[constantPoolOffset + index] = ((StdConstant)cpe).valueH;
				}
				else if(cpe.type == Type.wellKnownTypes[Type.txDouble]  && (cpe.accAndPropFlags & (1 << dpfConst)) != 0 && cpe instanceof StdConstant) { // TODO @Martin: is this correct???
					vrb.println(" ************ Inserting Double into CP: " + cpe.name);
					clazz.constantBlock[constantPoolOffset + index] = ((StdConstant)cpe).valueH;
					clazz.constantBlock[constantPoolOffset + index + 1] = ((StdConstant)cpe).valueL;
				}
			}
		}
		
		// 6 Checksum
		clazz.constantBlock[clazz.constantBlock.length - 1] = 0; // TODO implement crc32 checksum
		
		// print
	//	clazz.printConstantBlock();
		
		vrb.println("\n[LINKER] END: Creating constant block for class \"" + clazz.name +"\"\n");
	}

	public static void createSystemTable() {
		
		vrb.println("[LINKER] START: Creating systemtable:\n");
		
		int nOfStacks = Configuration.getNumberOfStacks();
		int nOfHeaps = Configuration.getNumberOfHeaps();
		
		vrb.println("  Number of stacks: " + nOfStacks);
		vrb.println("  Number of heaps: " + nOfHeaps);
		vrb.println("  Number of classes: " + Type.nofClasses);
		
		
		// create the systemtable
		systemTable = new int[systemTableSize];
		
		vrb.println("  Size of the system table: " + systemTable.length * 4 + " byte  -> array size: " + systemTable.length);
		
		
		// offset to the beginning of the class references
		systemTable[0] = 6 + 2 * nOfStacks + 2 * nOfHeaps;
		
		// offset to the beginning of the stack information 
		systemTable[1] = 5;
		
		// offset to the beginning of the heap information
		systemTable[2] = 5 + 2 * nOfStacks;
		
		Item c = Type.classList;
		
		HString kernelClassName = Configuration.getKernelClassname();
		Item kernelClinit = null;
		int kernelClinitAddr = 0;
		while(c != null && !c.name.equals(kernelClassName)) {
			c = c.next;
		}
		if(c != null) {
//			System.out.println("Kernel class name: " + c.name);
		//	kernelClinit = ((Class)c).methods.getItemByName("<clinit>");
			kernelClinit = ((Class)c).getClassConstructor();
			if(kernelClinit != null) {
//				System.out.println("kernelClinit: " + kernelClinit.name);
				kernelClinitAddr = kernelClinit.address;
			}
		}
		systemTable[3] = kernelClinitAddr;
		
		// number of stacks
		systemTable[4] = nOfStacks;
		
		// reference to each stack and the size of each stack
		for(int i = 0; i < nOfStacks; i++) {
			systemTable[5 + 2 * i] = Configuration.getStackSegments()[i].getBaseAddress();
			systemTable[5 + 2 * i + 1] = Configuration.getStackSegments()[i].getSize();
		}
		
		// number of heaps
		systemTable[5 + 2 * nOfStacks] = nOfHeaps;
		
		//reference to each heap and the size of each heap
		for(int i = 0; i < nOfHeaps; i++) {
			systemTable[6 + 2 * nOfStacks + 2 * i] = Configuration.getHeapSegments()[i].getBaseAddress();
			systemTable[6 + 2 * nOfStacks + 2 * i + 1] = Configuration.getHeapSegments()[i].getSize();
		}
		
		systemTable[7 + 2 * nOfStacks + 2 * nOfHeaps] = Type.nofClasses;
		
		// reference to the constant block of each class
//		Class clazz = Type.classList;
		Item item = Type.classList;
		int i = 7 + 2 * nOfStacks + 2 * nOfHeaps;
		while(item != null) {
			//systemTable[i] = clazz.address;
			if( item instanceof Class ){
				Class clazz = (Class)item;
				systemTable[i] = clazz.constSegment.getBaseAddress() + clazz.constOffset;
				
				i++;
			}
			item = item.next;
		}
		
		// End of system table -> should always be zero!
		systemTable[systemTable.length - 1] = 0;
		
		vrb.println("[LINKER] END: Creating systemtable\n");
	}
	
	public static void generateTargetImage() {
		
		vrb.println("[LINKER] START: Generating target image:\n");
		
		Item item = Type.classList;
		Method m;
		while(item != null) {
			if( item instanceof Class){
				Class clazz = (Class)item;
				vrb.println("  Proceeding class \"" + clazz.name + "\":");
				// code
				m = (Method)clazz.methods;
				vrb.println("    1) Code:");
				while(m != null) {
					if(m.machineCode != null) {
						vrb.println("         > Method \"" + m.name + "\":");
						clazz.codeSegment.tms.addData(clazz.codeSegment.getBaseAddress() + clazz.codeOffset + m.offset, m.machineCode.instructions, m.machineCode.iCount);
						addTargetMemorySegment(clazz.codeSegment.tms);
					}
					m = (Method)m.next;
				}
				
				// consts
				vrb.println("    2) Constantblock:");
				clazz.constSegment.tms.addData(clazz.constSegment.getBaseAddress() + clazz.constOffset, clazz.constantBlock);
				addTargetMemorySegment(clazz.constSegment.tms);
			}
			
			item = item.next;
		}

		vrb.println("  Proceeding system table:");
		Segment[] s = Configuration.getSysTabSegments();
		s[0].tms.addData(s[0].getBaseAddress(), systemTable);
		addTargetMemorySegment(s[0].tms);
		
		vrb.println("[LINKER] END: Generating target image\n");
	}
	
	/* ---------- private helper methods ---------- */
	
	private static void setBaseAddress(Segment s, int baseAddress) {
		//descend
		if(s.subSegments != null) setBaseAddress(s.subSegments, baseAddress);
		//set baseaddress
		if((s.getSize() > 0 && s.getRequiredSize() > 0) || ((s.getAttributes() & ((1 << atrStack) | (1 << atrHeap) | (1 << atrSysTab))) != 0)){ 
			s.setBaseAddress(baseAddress);
			s.tms = new TargetMemorySegment(s.getBaseAddress(), s.getSize());
			vrb.println("\t Segment "+s.getName() +" address = "+ Integer.toHexString(baseAddress) + ", size = " + s.getSize());
		}
		// traverse from left to right
		if(s.next != null) setBaseAddress(s.next, s.getSize()+ baseAddress);
	}

	private static Segment getFirstFittingSegment(Segment s, byte contentAttribute, int requiredSize) {
		Segment t = s;
		while(t != null) {
			if((t.getAttributes() & (1 << contentAttribute)) != 0) {
				if(t.subSegments != null) t = getFirstFittingSegment(t.subSegments, contentAttribute, requiredSize);
				if(t.getSize() <= 0 || t.getSize() - t.getRequiredSize() > requiredSize) return t;
			}
			t = t.next;
		}
		return null;
	}

	private static void setSegmentSize(Segment s) {
		if(s.lastSubSegment != null) {
			setSegmentSize(s.lastSubSegment);
		}
		if(s.getSize() <= 0) {
			s.setSize(roundUpToNextWord(s.getRequiredSize()));
		}
		else if(s.getSize() < s.getRequiredSize()) { 
			reporter.error(560, "Segment " + s.getName() + " is too small! Size is manually set to " + s.getSize() + " byte, but required size is " + s.getRequiredSize() + " byte!\n");
		}
//		System.out.println("  Segment " + s.getName() + ": size = " + s.getSize() + "byte!\n");
		if(s.prev != null) {
			setSegmentSize(s.prev);
		}
	}
	
	private static int getCorrectOffset(int potentialOffset, int size) {
		if(size == 8) size = 4; // handle double word items like word items
		if(potentialOffset % size == 0) return potentialOffset;
		return potentialOffset + (4 - (potentialOffset % size));
	}
	
	private static int roundUpToNextWord(int val) {
		return  (val + (slotSize-1) ) & -slotSize;
	}
	
	private static void addTargetMemorySegment(TargetMemorySegment tms) {
		if(targetImage == null) {
			targetImage = tms;
			lastTargetMemorySegment = tms;
		}
		else {
			TargetMemorySegment current = targetImage;
			while(current != null) {
				if(current == tms) return;
				current = current.next;
			}
			lastTargetMemorySegment.next = tms;
			lastTargetMemorySegment = lastTargetMemorySegment.next;
		}
	}
	
	
	/* ---------- debug primitives ---------- */
	
	public static void printSystemTable() {
		int i = 0;
		int nOfStacks = systemTable[4];
		int nOfHeaps = systemTable[5 + 2 * nOfStacks];
		int nOfClasses = Type.nofClasses;
		vrb.print("System table:\n");
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] classConstOffset\n"); i++;
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] stackOffset\n"); i++;
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] heapOffset\n"); i++;
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] kernelClinitAddr\n"); i++;
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] nofStacks\n"); i++;
		for(int j = 0; j < nOfStacks; j++) {
			vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] baseStack" + j + "\n"); i++;
			vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] sizeStack" + j + "\n"); i++;
		}
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] nofHeaps\n"); i++;
		for(int j = 0; j < nOfHeaps; j++) {
			vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] baseHeap" + j + "\n"); i++;
			vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] sizeHeap" + j + "\n"); i++;
		}
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] nofClasses\n"); i++;
		for(int j = 0; j < nOfClasses; j++) {
			vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] constBlkBaseClass" + j + "\n"); i++;
		}
		vrb.printf("  >%4d", i); vrb.print(" ["); vrb.printf("%8x", systemTable[i]); vrb.print("] endOfSysTab\n"); i++;
	}

	public static void printTargetImage() {
		TargetMemorySegment tms = targetImage;
		while(tms != null) {
			vrb.print(tms);
			tms = tms.next;
		}
	}

	public static void printClassList() {
		vrb.println("\n[LINKER] PRINT: This is a list of all classes with their methodes, fields and constant blocks\n");
		Method m;
		Item f;
		int cc = 0, mc = 0, fc = 0;
		Item item = Type.classList;
		while(item != null) {
			if( item instanceof Class){
				Class c = (Class)item;
				vrb.println("  Class: " + c.name + " (#" + cc++ + ")");
				vrb.println("    Number of class methods: " + c.nOfClassMethods);
				vrb.println("    Number of instance methods: " + c.nOfInstanceMethods);
				vrb.println("    Number of class fields: " + c.nOfClassFields);
				vrb.println("    Number of instance fields: " + c.nOfInstanceFields);
				vrb.println("    Number of interfaces: " + c.nOfInterfaces);
				vrb.println("    Number of base classes: " + c.nOfBaseClasses);
				vrb.println("    Number of references: " + c.nOfReferences);
				vrb.println("    Machine code size: " + c.machineCodeSize + " byte");
				vrb.println("    Constant block size: " + c.constantBlockSize + " byte");
				vrb.println("    Class fields size: " + c.classFieldsSize + " byte");
				vrb.println("    Code offset: 0x" + Integer.toHexString(c.codeOffset));
				vrb.println("    Var offset: 0x" + Integer.toHexString(c.varOffset));
				vrb.println("    Const offset: 0x" + Integer.toHexString(c.constOffset));
				vrb.println("    Code segment: " + c.codeSegment.getName() + " (Base address: 0x" + Integer.toHexString(c.codeSegment.getBaseAddress()) + ", size: " + c.codeSegment.getSize() + " byte)");
				vrb.println("    Var segment: " + c.varSegment.getName() + " (Base address: 0x" + Integer.toHexString(c.varSegment.getBaseAddress()) + ", size: " + c.varSegment.getSize() + " byte)");
				vrb.println("    Const segment: " + c.constSegment.getName() + " (Base address: 0x" + Integer.toHexString(c.constSegment.getBaseAddress()) + ", size: " + c.constSegment.getSize() + " byte)");
				vrb.println("    Class descriptor address: 0x" + Integer.toHexString(c.address));
				vrb.println("    Base address of the constant block: 0x" + Integer.toHexString(c.constSegment.getBaseAddress() + c.constOffset));
				vrb.println("    Base address of the code: 0x" + Integer.toHexString(c.codeSegment.getBaseAddress() + c.codeOffset));
				vrb.println("    Base address of the non constant class fields: 0x" + Integer.toHexString(c.varSegment.getBaseAddress() + c.varOffset));
				
				vrb.println("    Method list:");
				m = (Method)c.methods;
				mc = 0;
				if(m == null) vrb.println("      No methods in this class");
				else {
					while(m != null) {
						vrb.println("      > Method: " + m.name + " (#" + mc++ + ")");
						vrb.println("        Access and property flags: 0x" + Integer.toHexString(m.accAndPropFlags));
						if((m.accAndPropFlags & ((1 << dpfNew) | (1 << dpfUnsafe) | (1 << dpfSysPrimitive) | (1 << dpfSynthetic))) != 0) {	
							if((m.accAndPropFlags & (1 << dpfNew)) != 0) {
								vrb.println("        Special: New");
							}
							if((m.accAndPropFlags & (1 << dpfUnsafe)) != 0) {
								vrb.println("        Special: Unsafe");
							}
							if((m.accAndPropFlags & (1 << dpfSysPrimitive)) != 0) {
								vrb.println("        Special: System primitive");
							}
							if((m.accAndPropFlags & (1 << dpfSynthetic)) != 0) {
								vrb.println("        Special: Synthetic");
							}
							vrb.println("        Static: yes");
						}
						else {
							if((m.accAndPropFlags & (1 << apfStatic)) != 0) vrb.println("        Static: yes"); else vrb.println("        Static: no");
						}
						vrb.println("        address: 0x" + Integer.toHexString(m.address));
						vrb.println("        offset: 0x" + Integer.toHexString(m.offset));
						vrb.println("        index: 0x" + Integer.toHexString(m.index));
						if(m.machineCode != null)
							vrb.println("        Code size: 0x" + Integer.toHexString(m.machineCode.iCount * 4) + " (" + m.machineCode.iCount * 4 +" byte)");
						m = (Method)m.next;
					}
				}
				
				vrb.println("    Field list:");
				f = c.fields;
				fc = 0;
				if(f == null) vrb.println("      No fields in this class");
				else {
					while(f != null) {
						vrb.println("      > Field: " + f.name + " (#" + fc++ + ")");
						vrb.println("        Type: " + f.type.name);
						vrb.println("        Access and property flags: 0x" + Integer.toHexString(f.accAndPropFlags));
						if((f.accAndPropFlags & (1 << apfStatic)) != 0) vrb.println("        Static: yes"); else vrb.println("        Static: no");
						if((f.accAndPropFlags & (1 << dpfConst)) != 0) vrb.println("        Constant: yes"); else vrb.println("        Constant: no");
						vrb.println("        address: 0x" + Integer.toHexString(f.address));
						vrb.println("        offset: 0x" + Integer.toHexString(f.offset));
						vrb.println("        index: 0x" + Integer.toHexString(f.index));
						f = f.next;
					}
				}
				
				vrb.println("    Constant block:");
				c.printConstantBlock(2);
			}
			
			item = item.next;
			
			vrb.println("  ----------------------------------------------------------------------");
		}
		vrb.println("\n[LINKER] PRINT: End of class list\n");
	}

}
