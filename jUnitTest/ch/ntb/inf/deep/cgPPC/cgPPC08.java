package ch.ntb.inf.deep.cgPPC;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.BeforeClass;
import org.junit.Test;

import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.classItems.Type;
import ch.ntb.inf.deep.config.Configuration;

public class cgPPC08 extends TestCgPPC {

	@BeforeClass
	public static void setUp() {
		String workspace =System.getProperty("user.dir")+ "/bin";
		String[] rootClassNames = new String[] { "ch/ntb/inf/deep/testClasses/T08Calls" };
		Configuration.parseAndCreateConfig(config[0], config[1]);
		try {
			Class.buildSystem(rootClassNames,new String[]{workspace, "../bsp/bin"},Configuration.getSystemPrimitives(), (1 << atxCode)
					| (1 << atxLocalVariableTable)
					| (1 << atxLineNumberTable)
					| (1 << atxExceptions));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (Type.nofRootClasses > 0) {
			createCgPPC(Type.rootClasses[0]);
		}
	}

//	@Ignore
	@Test
	public void clinit() {
		int[] code = getCode("<clinit>");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, -128"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r3, r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stb  r2, 0(r3)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 1"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r3, 4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r3, r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stb  r2, 0(r3)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 16"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}
	
//	@Ignore
	@Test
	public void init() {
		int[] code = getCode("<init>");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -32(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r31, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r31, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r31"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 31000"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r31, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("sth  r2, 0(r31)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 32"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}
	
//	@Ignore
	@Test
	public void classMethCall() {
		int[] code = getCode("classMethCall");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -32(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r31, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r3, r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl  always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r4, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 6"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lbz  r3, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("extsb  r3, r3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r2, r3, 100"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("extsb  r3, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 11"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stb  r3, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("add  r2, r31, r4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 32"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}
	
//	@Ignore
	@Test
	public void objectMethCall() {
		int[] code = getCode("objectMethCall");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -32(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl  always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r31, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r31, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 7"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r31"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl  always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 9"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r31, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r3, -4(r31)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r3, -12(r3)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r3, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r31"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl  always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r4, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lmw  r31, 16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 32"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}
	
//	@Ignore
	@Test
	public void callToAnotherClass() {
		int[] code = getCode("callToAnotherClass");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclrl  always, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 16"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}
	
	@Test
	public void classMethod() {
		int[] code = getCode("classMethod");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -16(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r3, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r4, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("b  12"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r3, r3, 3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r4, r4, 1"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("cmp crf0, 0, r4, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bc  iftrue, CRF0[LT], -12"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 12(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 16"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}

	@Test
	public void objectMethod() {
		int[] code = getCode("objectMethod");
		int i = 0;
		assertEquals("wrong instruction", InstructionDecoder.getCode("stwu  r1, -32(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mfspr  r0, LR"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stw  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lha  r5, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r4, r5, 200"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("extsh  r5, r4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("sth  r5, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lbz  r4, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("extsb  r4, r4"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r2, r4, 300"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("extsb  r4, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 9"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addis  r2, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("stb  r4, 0(r2)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("li  r2, 2"), code[i++]);	
		assertEquals("wrong instruction", InstructionDecoder.getCode("twi  ifequal, r2, 0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("divw  r4, r3, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mullw  r4, r4, r2"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("subf  r5, r4, r3"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lr  r2, r5"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("lwz  r0, 28(r1)"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("mtspr  LR, r0"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("addi  r1, r1, 32"), code[i++]);
		assertEquals("wrong instruction", InstructionDecoder.getCode("bclr  always, 0"), code[i++]);
	}

}
