package ch.ntb.inf.deep.ssa;

import java.io.IOException;

import org.junit.BeforeClass;
import org.junit.Test;

import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.classItems.Type;

public class SSA08 extends TestSSA {
	@BeforeClass
	public static void setUp() {
		String[] rootClassNames = new String[] { "ch/ntb/inf/deep/testClasses/T08Calls" };
		try {
			Class.buildSystem(rootClassNames, (1 << atxCode)
					| (1 << atxLocalVariableTable)
					| (1 << atxLineNumberTable)
					| (1 << atxExceptions));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (Type.nofRootClasses > 0) {
			createSSA(Type.rootClasses[0]);
		}
	}
	@Test
	public void testClassConstructor(){
		SSANode[] nodes = getAndTestSSA(0, 1, 0);
		testNode(nodes[0], 5, 0, 1);
	}

	@Test
	public void testConstructor() {
		SSANode[] nodes = getAndTestSSA(5, 1, 0);
		testNode(nodes[0], 5, 0, 3);
	}
	
	@Test
	public void testClassMethCall(){
		SSANode[] nodes = getAndTestSSA(1, 1, 0);
		testNode(nodes[0], 10, 0, 4);
	}
	
	@Test
	public void testObjectMethCall(){
		SSANode[] nodes = getAndTestSSA(2, 1, 0);
		testNode(nodes[0], 5, 0, 3);
	}
	
	@Test
	public void testCallToAnotherClass(){
		SSANode[] nodes = getAndTestSSA(3, 1, 0);
		testNode(nodes[0], 2, 0, 0);
	}

}
