﻿/*
 * Copyright (c) 2011 NTB Interstate University of Applied Sciences of Technology Buchs.
 *
 * http://www.ntb.ch/inf
 * 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Eclipse Public License for more details.
 * 
 * Contributors:
 *     NTB - initial implementation
 * 
 */

package ch.ntb.inf.deep.cgPPC;

import ch.ntb.inf.deep.cfg.CFGNode;
import ch.ntb.inf.deep.classItems.*;
import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.config.Configuration;
import ch.ntb.inf.deep.host.ErrorReporter;
import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.linker.Linker32;
import ch.ntb.inf.deep.ssa.*;
import ch.ntb.inf.deep.ssa.instruction.*;
import ch.ntb.inf.deep.strings.HString;

public class CodeGen implements SSAInstructionOpcs, SSAInstructionMnemonics, SSAValueType, InstructionOpcs, Registers, ICjvmInstructionOpcs, ICclassFileConsts {
	private static final boolean dbg = false;

	static final int maxNofParam = 32;
	private static final int defaultNofInstr = 32;
	private static final int defaultNofFixup = 8;
	private static final int arrayLenOffset = 6;	

	private static int objectSize, stringSize;
	private static StdConstant int2floatConst1 = null;	// 2^52+2^31, for int -> float conversions
	private static StdConstant int2floatConst2 = null;	// 2^32, for long -> float conversions
	private static StdConstant int2floatConst3 = null;	// 2^52, for long -> float conversions
	
	private static int idGET1, idGET2, idGET4, idGET8;
	private static int idPUT1, idPUT2, idPUT4, idPUT8;
	private static int idBIT, idASM, idHALT, idADR_OF_METHOD;
	static int idENABLE_FLOATS;
	static int idGETGPR, idGETFPR, idGETSPR;
	static int idPUTGPR, idPUTFPR, idPUTSPR;
	static int idDoubleToBits, idBitsToDouble;
	
	private static Method stringNewstringMethod;
	private static Method heapNewstringMethod;
	private static Method strInitC;
	private static Method strInitCII;
	private static Method strAllocC;
	private static Method strAllocCII;

	private static int LRoffset;	
	private static int CTRoffset;	
	private static int CRoffset;	
	private static int SRR0offset;	
	private static int SRR1offset;	
	private static int paramOffset;
	private static int GPRoffset;	
	private static int FPRoffset;	
	private static int localVarOffset;
	private static int tempStorageOffset;	
	private static int stackSize;
	static boolean tempStorage;
	static boolean enFloatsInExc;
	
	// nof parameter for a method, set by SSA, includes "this", long and doubles count as 2 parameters
	private static int nofParam;	
	// nofParamGPR + nofParamFPR = nofParam, set by last exit set of last node
	private static int nofParamGPR, nofParamFPR;	 
	// maximum nof registers used by this method
	static int nofNonVolGPR, nofNonVolFPR, nofVolGPR, nofVolFPR;
	// gives required stack space for parameters of this method if not enough registers
	static int recParamSlotsOnStack;
	// gives required stack space for parameters of any call in this method if not enough registers
	static int callParamSlotsOnStack;
	// type of parameter, set by SSA, includes "this", long and doubles count as 2 parameters
	static int[] paramType = new int[maxNofParam];
	// register type of parameter, long and doubles count as 2 parameters
	static boolean[] paramHasNonVolReg = new boolean[maxNofParam];
	// register of parameter, long and doubles count as 2 parameters
	static int[] paramRegNr = new int[maxNofParam];
	// last instruction where parameters is used
	static int[] paramRegEnd = new int[maxNofParam];
	
	// information about into which registers parameters of this method go 
	private static int nofMoveGPR, nofMoveFPR;
	private static int[] moveGPRsrc = new int[maxNofParam];
	private static int[] moveGPRdst = new int[maxNofParam];
	private static int[] moveFPRsrc = new int[maxNofParam];
	private static int[] moveFPRdst = new int[maxNofParam];
	
	// information about the src registers for parameters of a call to a method within this method
	private static int[] srcGPR = new int[nofGPR];
	private static int[] srcFPR = new int[nofFPR];
	private static int[] srcGPRcount = new int[nofGPR];
	private static int[] srcFPRcount = new int[nofFPR];
	
	private static SSAValue[] lastExitSet;
	private static boolean newString;
	private static Item stringRef;
	
	public SSA ssa;	// reference to the SSA of a method
	public int[] instructions;	//contains machine instructions for the ssa of a method
	public int iCount;	//nof instructions for this method
	
	Item[] fixups;	// contains all references whose address has to be fixed by the linker
	int fCount;	//nof fixups
	int lastFixup;	// instr number where the last fixup is found

	public CodeGen(SSA ssa) {
		this.ssa = ssa;
		instructions = new int[defaultNofInstr];
		fixups = new Item[defaultNofFixup];
		nofParamGPR = 0; nofParamFPR = 0;
		nofNonVolGPR = 0; nofNonVolFPR = 0;
		nofVolGPR = 0; nofVolFPR = 0;
		nofMoveGPR = 0; nofMoveFPR = 0;
		tempStorage = false;
		enFloatsInExc = false;
		recParamSlotsOnStack = 0; callParamSlotsOnStack = 0;
		if (dbg) StdStreams.vrb.println("generate code for " + ssa.cfg.method.owner.name + "." + ssa.cfg.method.name);
		for (int i = 0; i < maxNofParam; i++) {
			paramType[i] = tVoid;
			paramRegNr[i] = -1;
			paramRegEnd[i] = -1;
		}

		// make local copy
		int maxStackSlots = ssa.cfg.method.maxStackSlots;
		int i = maxStackSlots;
		while ((i < ssa.isParam.length) && ssa.isParam[i]) {
			int type = ssa.paramType[i] & ~(1<<ssaTaFitIntoInt);
			paramType[i - maxStackSlots] = type;
			paramHasNonVolReg[i - maxStackSlots] = false;
			if (type == tLong || type == tDouble) i++;
			i++;
		}
		nofParam = i - maxStackSlots;
		if (nofParam > maxNofParam) {ErrorReporter.reporter.error(601); return;}
		if (dbg) StdStreams.vrb.println("nofParam = " + nofParam);
		
		if (dbg) StdStreams.vrb.println("build intervals");
//		ssa.cfg.printToLog();
//		ssa.print(0);
		RegAllocator.buildIntervals(ssa);
//		if (dbg) {
//			StdStreams.vrb.println("phi functions resolved");
//			RegAllocator.printJoins();
//		}
//		ssa.print(0);
		
		if(dbg) StdStreams.vrb.println("assign registers to parameters");
		SSANode b = (SSANode) ssa.cfg.rootNode;
		while (b.next != null) {
			b = (SSANode) b.next;
		}	
		lastExitSet = b.exitSet;
		// determine, which parameters go into which register
		parseExitSet(lastExitSet, maxStackSlots);
		if(dbg) {
			StdStreams.vrb.print("parameter go into register: ");
			for (int n = 0; paramRegNr[n] != -1; n++) StdStreams.vrb.print(paramRegNr[n] + "  "); 
			StdStreams.vrb.println();
		}
		
		if(dbg) StdStreams.vrb.println("allocate registers");
		RegAllocator.assignRegisters(this);
		if (dbg) {
			StdStreams.vrb.println("phi functions resolved");
			RegAllocator.printJoins();
		}
		if(dbg) {
			StdStreams.vrb.print("register usage in method: nofNonVolGPR = " + nofNonVolGPR + ", nofVolGPR = " + nofVolGPR);
			StdStreams.vrb.println(", nofNonVolFPR = " + nofNonVolFPR + ", nofVolFPR = " + nofVolFPR);
			StdStreams.vrb.print("register usage for parameters: nofParamGPR = " + nofParamGPR + ", nofParamFPR = " + nofParamFPR);
			StdStreams.vrb.println(", receive parameters slots on stack = " + recParamSlotsOnStack);
			StdStreams.vrb.println("max. parameter slots for any call in this method = " + callParamSlotsOnStack);
			StdStreams.vrb.print("parameter end at instr no: ");
			for (int n = 0; n < nofParam; n++) 
				if (paramRegEnd[n] != -1) StdStreams.vrb.print(paramRegEnd[n] + "  "); 
			StdStreams.vrb.println();
		}
		if ((ssa.cfg.method.accAndPropFlags & (1 << dpfExcHnd)) != 0) {	// exception
			if (ssa.cfg.method.name.equals(HString.getHString("reset"))) {	// reset has no prolog
			} else {
				stackSize = calcStackSizeException();
				insertPrologException();
			}
		} else {
			stackSize = calcStackSize();
			insertProlog();
		}
		
		SSANode node = (SSANode)ssa.cfg.rootNode;
		while (node != null) {
			node.codeStartAddr = iCount;
			translateSSA(node);
			node.codeEndAddr = iCount-1;
			node = (SSANode) node.next;
		}
		node = (SSANode)ssa.cfg.rootNode;
		while (node != null) {	// resolve local branch targets
			if (node.nofInstr > 0) {
				if (node.instructions[node.nofInstr-1].ssaOpcode == sCbranch) {
					int code = this.instructions[node.codeEndAddr];
					CFGNode[] successors = node.successors;
					switch (code & 0xfc000000) {
					case ppcB:			
						if ((code & 0xffff) != 0) {	// switch
							int nofCases = (code & 0xffff) >> 2;
							int k;
							for (k = 0; k < nofCases; k++) {
								int branchOffset = ((SSANode)successors[k]).codeStartAddr - (node.codeEndAddr+1-(nofCases-k)*2);
								this.instructions[node.codeEndAddr+1-(nofCases-k)*2] |= (branchOffset << 2) & 0x3ffffff;
							}
							int branchOffset = ((SSANode)successors[k]).codeStartAddr - node.codeEndAddr;
							this.instructions[node.codeEndAddr] &= 0xfc000000;
							this.instructions[node.codeEndAddr] |= (branchOffset << 2) & 0x3ffffff;
						} else {
							int branchOffset = ((SSANode)successors[0]).codeStartAddr - node.codeEndAddr;
							this.instructions[node.codeEndAddr] |= (branchOffset << 2) & 0x3ffffff;
						}
						break;
					case ppcBc:
						int branchOffset = ((SSANode)successors[1]).codeStartAddr - node.codeEndAddr;
						this.instructions[node.codeEndAddr] |= (branchOffset << 2) & 0xffff;
						break;
					}
				} else if (node.instructions[node.nofInstr-1].ssaOpcode == sCreturn) {
					if (node.next != null) {
						int branchOffset = iCount - node.codeEndAddr;
						this.instructions[node.codeEndAddr] |= (branchOffset << 2) & 0x3ffffff;
					}
				}
			}
			node = (SSANode) node.next;
		}
		if ((ssa.cfg.method.accAndPropFlags & (1 << dpfExcHnd)) != 0) {	// exception
			if (ssa.cfg.method.name.equals(HString.getHString("reset"))) {	// reset needs no epilog
			} else {
				insertEpilogException(stackSize);
			}
		} else {
			insertEpilog(stackSize);
		}
		if (dbg) {ssa.print(0); StdStreams.vrb.print(toString());}
	}

	private static void parseExitSet(SSAValue[] exitSet, int maxStackSlots) {
		nofParamGPR = 0; nofParamFPR = 0;
		nofMoveGPR = 0; nofMoveFPR = 0;
		if(dbg) StdStreams.vrb.print("[");
		for (int i = 0; i < nofParam; i++) {
			int type = paramType[i];
			if(dbg) StdStreams.vrb.print("(" + svNames[type] + ")");
			if (type == tLong) {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					if(dbg) StdStreams.vrb.print("r");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocator.reserveReg(gpr, true);
						int regLong = RegAllocator.reserveReg(gpr, true);
						moveGPRsrc[nofMoveGPR] = nofParamGPR;
						moveGPRsrc[nofMoveGPR+1] = nofParamGPR+1;
						moveGPRdst[nofMoveGPR++] = reg;
						moveGPRdst[nofMoveGPR++] = regLong;
						paramRegNr[i] = reg;
						paramRegNr[i+1] = regLong;
						if(dbg) StdStreams.vrb.print(reg + ",r" + regLong);
					} else {
						int reg = paramStartGPR + nofParamGPR;
						if (reg <= paramEndGPR) RegAllocator.reserveReg(gpr, reg);
						else {
							reg = RegAllocator.reserveReg(gpr, false);
							moveGPRsrc[nofMoveGPR] = nofParamGPR;
							moveGPRdst[nofMoveGPR++] = reg;
						}
						int regLong = paramStartGPR + nofParamGPR + 1;
						if (regLong <= paramEndGPR) RegAllocator.reserveReg(gpr, regLong);
						else {
							regLong = RegAllocator.reserveReg(gpr, false);
							moveGPRsrc[nofMoveGPR] = nofParamGPR + 1;
							moveGPRdst[nofMoveGPR++] = regLong;
						}
						paramRegNr[i] = reg;
						paramRegNr[i+1] = regLong;
						if(dbg) StdStreams.vrb.print(reg + ",r" + regLong);
					}
				}
				nofParamGPR += 2;	// see comment below for else type 
				i++;
			} else if (type == tFloat || type == tDouble) {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					if(dbg) StdStreams.vrb.print("fr");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocator.reserveReg(fpr, true);
						moveFPRsrc[nofMoveFPR] = nofParamFPR;
						moveFPRdst[nofMoveFPR++] = reg;
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					} else {
						int reg = paramStartFPR + nofParamFPR;
						if (reg <= paramEndFPR) RegAllocator.reserveReg(fpr, reg);
						else {
							reg = RegAllocator.reserveReg(fpr, false);
							moveFPRsrc[nofMoveFPR] = nofParamFPR;
							moveFPRdst[nofMoveFPR++] = reg;
						}
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					}
				}
				nofParamFPR++;	// see comment below for else type 
				if (type == tDouble) i++;
			} else {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					if(dbg) StdStreams.vrb.print("r");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocator.reserveReg(gpr, true);
						moveGPRsrc[nofMoveGPR] = nofParamGPR;
						moveGPRdst[nofMoveGPR++] = reg;
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					} else {
						int reg = paramStartGPR + nofParamGPR;
						if (reg <= paramEndGPR) RegAllocator.reserveReg(gpr, reg);
						else {
							reg = RegAllocator.reserveReg(gpr, false);
							moveGPRsrc[nofMoveGPR] = nofParamGPR;
							moveGPRdst[nofMoveGPR++] = reg;
						}
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					}
				}
				nofParamGPR++;	// even if the parameter is not used, the calling method
				// assigns a register and we have to do here the same
			}
			if (i < nofParam - 1) if(dbg) StdStreams.vrb.print(", ");
		}
		int nof = nofParamGPR - (paramEndGPR - paramStartGPR + 1);
		if (nof > 0) recParamSlotsOnStack = nof;
		nof = nofParamFPR - (paramEndFPR - paramStartFPR + 1);
		if (nof > 0) recParamSlotsOnStack += nof*2;
		
		if(dbg) StdStreams.vrb.println("]");
	}

	private static int calcStackSize() {
		int size = 16 + callParamSlotsOnStack * 4 + nofNonVolGPR * 4 + nofNonVolFPR * 8 + (tempStorage? 8 : 0);
		if (enFloatsInExc) size += nonVolStartFPR * 8 + 8;	// save volatile FPR's and FPSCR
		int padding = (16 - (size % 16)) % 16;
		size = size + padding;
		LRoffset = size - 4;
		GPRoffset = size - 12 - nofNonVolGPR * 4;
		FPRoffset = GPRoffset - nofNonVolFPR * 8;
		if (enFloatsInExc) FPRoffset -= nonVolStartFPR * 8 + 8;
		if (tempStorage) tempStorageOffset = FPRoffset - 8;
		else tempStorageOffset = FPRoffset;
		paramOffset = 4;
		return size;
	}

	private static int calcStackSizeException() {
		int size = 24 + nofGPR * 4 + (tempStorage? 8 : 0);
		if (enFloatsInExc) {
			size += nofNonVolFPR * 8;	// save used nonvolatile FPR's
			size += nonVolStartFPR * 8 + 8;	// save all volatile FPR's and FPSCR
		}
		int padding = (16 - (size % 16)) % 16;
		size = size + padding;
		LRoffset = size - 4;
		CTRoffset = size - 8;
		CRoffset = size - 12;
		SRR0offset = size - 20;
		SRR1offset = size - 16;
		GPRoffset = size - 20 - nofGPR * 4;
		FPRoffset = GPRoffset - nofNonVolFPR * 8;
		if (enFloatsInExc) FPRoffset -= nonVolStartFPR * 8 + 8;
		if (tempStorage) tempStorageOffset = FPRoffset - 8;
		else tempStorageOffset = FPRoffset;
		paramOffset = 4;
		return size;
	}

	private void translateSSA (SSANode node) {
		SSAValue[] opds;
		int sReg1, sReg2, dReg, refReg, indexReg, valReg, bci, offset, type, stringCharOffset, strReg=0;
		Item stringCharRef = null;
		for (int i = 0; i < node.nofInstr; i++) {
			SSAInstruction instr = node.instructions[i];
			SSAValue res = instr.result;
			instr.machineCodeOffset = iCount;
			
//			if (dbg) StdStreams.vrb.println("ssa opcode at " + instr.result.n + ": " + instr.scMnemonics[instr.ssaOpcode]);
			switch (instr.ssaOpcode) { 
			case sCloadConst:
				opds = instr.getOperands();
				dReg = res.reg;
				if (dReg >= 0) {	// else immediate opd
					switch (res.type & ~(1<<ssaTaFitIntoInt)) {
					case tByte: case tShort: case tInteger:
						int immVal = ((StdConstant)res.constant).valueH;
						loadConstant(immVal, dReg);
					break;
					case tLong:	
						StdConstant constant = (StdConstant)res.constant;
						long immValLong = ((long)(constant.valueH)<<32) | (constant.valueL&0xFFFFFFFFL);
						loadConstant((int)(immValLong >> 32), res.regLong);
						loadConstant((int)immValLong, dReg);
						break;	
					case tFloat:	// load from const pool
						constant = (StdConstant)res.constant;
						if (constant.valueH == 0) {	// 0.0 must be loaded directly as it's not in the cp
							createIrDrAsimm(ppcAddi, res.regAux1, 0, 0);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset);
							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
						} else if (constant.valueH == 0x3f800000) {	// 1.0
							createIrDrAsimm(ppcAddis, res.regAux1, 0, 0x3f80);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset);
							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
						} else if (constant.valueH == 0x40000000) {	// 2.0
							createIrDrAsimm(ppcAddis, res.regAux1, 0, 0x4000);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset);
							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
						} else {
							loadConstantAndFixup(res.regAux1, constant);
							createIrDrAd(ppcLfs, res.reg, res.regAux1, 0);
						}
						break;
					case tDouble:
						constant = (StdConstant)res.constant;
						if (constant.valueH == 0) {	// 0.0 must be loaded directly as it's not in the cp
							createIrDrAsimm(ppcAddi, res.regAux1, 0, 0);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset+4);
							createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
						} else if (constant.valueH == 0x3ff00000) {	// 1.0{
							createIrDrAsimm(ppcAddis, res.regAux1, 0, 0x3ff0);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset);
							createIrDrAsimm(ppcAddis, res.regAux1, 0, 0);
							createIrSrAd(ppcStw, res.regAux1, stackPtr, tempStorageOffset+4);
							createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
						} else {
							loadConstantAndFixup(res.regAux1, constant);
							createIrDrAd(ppcLfd, res.reg, res.regAux1, 0);
						}
						break;
					case tRef:
						if (res.constant == null) // object = null
							loadConstant(0, dReg);
						else	// ref to constant string
							loadConstantAndFixup(res.reg, res.constant);
						break;
					default:
						ErrorReporter.reporter.error(610);
						assert false : "result of SSA instruction has wrong type";
						return;
					}
				} else 
				break;	// sCloadConst
			case sCloadLocal:
				break;	// sCloadLocal
			case sCloadFromField:
				opds = instr.getOperands();
				offset = 0;			
				if (opds == null) {	// getstatic
					sReg1 = res.regAux1;
					Item field = ((NoOpndRef)instr).field;
					loadConstantAndFixup(sReg1, field);
				} else {	// getfield
					if ((ssa.cfg.method.owner == Type.wktString) &&	// string access needs special treatment
							((MonadicRef)instr).item.name.equals(HString.getHString("value"))) {
						createIrArSrB(ppcOr, res.reg, opds[0].reg, opds[0].reg);	// result contains ref to string
						stringCharRef = ((MonadicRef)instr).item;	// ref to "value"
						stringCharOffset = ((MonadicRef)instr).item.index;	// offset is start of char array
						break;	
					} else {
						sReg1 = opds[0].reg;
						offset = ((MonadicRef)instr).item.offset;
						createItrap(ppcTwi, TOifequal, sReg1, 0);
					}
				}
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tBoolean: case tByte:
					createIrDrAd(ppcLbz, res.reg, sReg1, offset);
					createIrArS(ppcExtsb, res.reg, res.reg);
					break;
				case tShort: 
					createIrDrAd(ppcLha, res.reg, sReg1, offset);
					break;
				case tInteger: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					createIrDrAd(ppcLwz, res.reg, sReg1, offset);
					break;
				case tChar: 
					createIrDrAd(ppcLhz, res.reg, sReg1, offset);
					break;
				case tLong:
					createIrDrAd(ppcLwz, res.regLong, sReg1, offset);
					createIrDrAd(ppcLwz, res.reg, sReg1, offset + 4);
					break;
				case tFloat: 
					createIrDrAd(ppcLfs, res.reg, sReg1, offset);
					break;
				case tDouble: 
					createIrDrAd(ppcLfd, res.reg, sReg1, offset);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCloadFromField
			case sCloadFromArray:
				opds = instr.getOperands();
				if (ssa.cfg.method.owner == Type.wktString && opds[0].owner instanceof MonadicRef && ((MonadicRef)opds[0].owner).item == stringCharRef) {	// string access needs special treatment
					indexReg = opds[1].reg;	// index into array
					createIrDrAd(ppcLwz, res.regAux1, opds[0].reg, objectSize);	// read field "count", must be first field
					createItrap(ppcTw, TOifgeU, indexReg, res.regAux1);
					switch (res.type & 0x7fffffff) {	// type to read
					case tByte:
						createIrDrAsimm(ppcAddi, res.regAux2, opds[0].reg, stringSize - 4);	// add index of field "value" to index
						createIrDrArB(ppcLbzx, res.reg, res.regAux2, indexReg);
						createIrArS(ppcExtsb, res.reg, res.reg);
						break;
					case tChar: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 1, 0, 30);
						createIrDrAsimm(ppcAddi, res.regAux2, opds[0].reg, stringSize - 4);	// add index of field "value" to index
						createIrDrArB(ppcLhzx, res.reg, res.regAux1, res.regAux2);
						break;
					default:
						ErrorReporter.reporter.error(610);
						assert false : "result of SSA instruction has wrong type";
						return;
					}
				} else {
					refReg = opds[0].reg;	// ref to array
					indexReg = opds[1].reg;	// index into array
					createItrap(ppcTwi, TOifequal, refReg, 0);
					createIrDrAd(ppcLha, res.regAux1, refReg, -arrayLenOffset);
					createItrap(ppcTw, TOifgeU, indexReg, res.regAux1);
					switch (res.type & ~(1<<ssaTaFitIntoInt)) {	// type to read
					case tByte: case tBoolean:
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLbzx, res.reg, res.regAux2, indexReg);
						createIrArS(ppcExtsb, res.reg, res.reg);
						break;
					case tShort: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 1, 0, 30);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLhax, res.reg, res.regAux1, res.regAux2);
						break;
					case tInteger: case tRef: case tAref: case tAchar: case tAfloat: 
					case tAdouble: case tAbyte: case tAshort: case tAinteger: case tAlong:
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 2, 0, 29);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLwzx, res.reg, res.regAux1, res.regAux2);
						break;
					case tLong: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 3, 0, 28);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLwzux, res.regLong, res.regAux1, res.regAux2);
						createIrDrAd(ppcLwz, res.reg, res.regAux1, 4);
						break;
					case tFloat:
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 2, 0, 29);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLfsx, res.reg, res.regAux1, res.regAux2);
						break;
					case tDouble: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 3, 0, 28);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLfdx, res.reg, res.regAux1, res.regAux2);
						break;
					case tChar: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 1, 0, 30);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrDrArB(ppcLhzx, res.reg, res.regAux1, res.regAux2);
						break;
					default:
						ErrorReporter.reporter.error(610);
						assert false : "result of SSA instruction has wrong type";
						return;
					}
				}
				break;	// sCloadFromArray
			case sCstoreToField:
				opds = instr.getOperands();
				if (opds.length == 1) {	// putstatic
					sReg1 = opds[0].reg;
					sReg2 = opds[0].regLong;
					refReg = res.regAux1;
					Item item = ((MonadicRef)instr).item;
					if(((Type)item.type).category == 'P')
						type = Type.getPrimitiveTypeIndex(item.type.name.charAt(0));
					else type = tRef; //is a Array or a Object //TODO @Urs please check this again 24.03.2011!!!!!!!!!!!!!!
					offset = 0;
					loadConstantAndFixup(res.regAux1, item);
				} else {	// putfield
					refReg = opds[0].reg;
					sReg1 = opds[1].reg;
					sReg2 = opds[1].regLong;
					if(((Type)((DyadicRef)instr).field.type).category == 'P')
						type = Type.getPrimitiveTypeIndex(((DyadicRef)instr).field.type.name.charAt(0));
					else type = tRef;//is a Array or a Object //TODO @Urs please check this again 24.03.2011!!!!!!!!!!!!!!
					offset = ((DyadicRef)instr).field.offset;
					createItrap(ppcTwi, TOifequal, refReg, 0);
				}
				switch (type) {
				case tBoolean: case tByte: 
					createIrSrAd(ppcStb, sReg1, refReg, offset);
					break;
				case tShort: case tChar:
					createIrSrAd(ppcSth, sReg1, refReg, offset);
					break;
				case tInteger: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					createIrSrAd(ppcStw, sReg1, refReg, offset);
					break;
				case tLong:
					createIrSrAd(ppcStw, sReg2, refReg, offset);
					createIrSrAd(ppcStw, sReg1, refReg, offset + 4);
					break;
				case tFloat: 
					createIrSrAd(ppcStfs, sReg1, refReg, offset);
					break;
				case tDouble: 
					createIrSrAd(ppcStfd, sReg1, refReg, offset);
					break;
				default:
					ErrorReporter.reporter.error(611);
					assert false : "operand of SSA instruction has wrong type";
					return;
				}
				break;	// sCstoreToField
			case sCstoreToArray:
				opds = instr.getOperands();
				refReg = opds[0].reg;	// ref to array
				indexReg = opds[1].reg;	// index into array
				valReg = opds[2].reg;	// value to store
				if (ssa.cfg.method.owner == Type.wktString && opds[0].owner instanceof MonadicRef && ((MonadicRef)opds[0].owner).item == stringCharRef) {	// string access needs special treatment
					indexReg = opds[1].reg;	// index into array
//					createIrDrAd(ppcLwz, res.regAux1, opds[0].reg, objectSize);	// read field "count", must be first field
//					createItrap(ppcTw, TOifgeU, indexReg, res.regAux1);
//					switch (opds[2].type & 0x7fffffff) {
//					case tByte:
//						createIrDrAsimm(ppcAddi, res.regAux2, opds[0].reg, stringSize - 4);	// add index of field "value" to index
//						createIrSrArB(ppcStbx, valReg, indexReg, res.regAux2);
//						break;
//					case tChar: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 1, 0, 30);
						createIrDrAsimm(ppcAddi, res.regAux2, opds[0].reg, stringSize - 4);	// add index of field "value" to index
						createIrSrArB(ppcSthx, valReg, res.regAux1, res.regAux2);
//						break;
//					default:
//						assert false : "cg: type not implemented";
//					}
				} else {
					createItrap(ppcTwi, TOifequal, refReg, 0);
					createIrDrAd(ppcLha, res.regAux1, refReg, -arrayLenOffset);
					createItrap(ppcTw, TOifgeU, indexReg, res.regAux1);
					switch (opds[0].type & ~(1<<ssaTaFitIntoInt)) {
					case tAbyte: case tAboolean:
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcStbx, valReg, indexReg, res.regAux2);
						break;
					case tAshort: case tAchar: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 1, 0, 30);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcSthx, valReg, res.regAux1, res.regAux2);
						break;
					case tAref: case tRef: case tAinteger:
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 2, 0, 29);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcStwx, valReg, res.regAux1, res.regAux2);
						break;
					case tAlong: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 3, 0, 28);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcStwux, opds[2].regLong, res.regAux1, res.regAux2);
						createIrSrAd(ppcStw, valReg, res.regAux1, 4);
						break;
					case tAfloat:  
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 2, 0, 29);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcStfsx, valReg, res.regAux1, res.regAux2);
						break;
					case tAdouble: 
						createIrArSSHMBME(ppcRlwinm, res.regAux1, indexReg, 3, 0, 28);
						createIrDrAsimm(ppcAddi, res.regAux2, refReg, objectSize);
						createIrSrArB(ppcStfdx, valReg, res.regAux1, res.regAux2);
						break;
					default:
						ErrorReporter.reporter.error(611);
						assert false : "operand of SSA instruction has wrong type";
						return;
					}
				}
				break;	// sCstoreToArray
			case sCadd:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createIrDrAsimm(ppcAddi, dReg, sReg2, immVal);
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createIrDrAsimm(ppcAddi, dReg, sReg1, immVal);
					} else {
						createIrDrArB(ppcAdd, dReg, sReg1, sReg2);
					}
					break;
				case tLong:
					createIrDrArB(ppcAddc, dReg, sReg1, sReg2);
					createIrDrArB(ppcAdde, res.regLong, opds[0].regLong, opds[1].regLong);
					break;
				case tFloat:
					createIrDrArB(ppcFadds, dReg, sReg1, sReg2);
					break;
				case tDouble:
					createIrDrArB(ppcFadd, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	//sCadd
			case sCsub:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createIrDrAsimm(ppcSubfic, dReg, sReg2, immVal);
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createIrDrAsimm(ppcAddi, dReg, sReg1, -immVal);
					} else {
						createIrDrArB(ppcSubf, dReg, sReg2, sReg1);
					}
					break;
				case tLong:
					createIrDrArB(ppcSubfc, dReg, sReg2, sReg1);
					createIrDrArB(ppcSubfe, res.regLong, opds[1].regLong, opds[0].regLong);
					break;
				case tFloat:
					createIrDrArB(ppcFsubs, dReg, sReg1, sReg2);
					break;
				case tDouble:
					createIrDrArB(ppcFsub, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCsub
			case sCmul:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createIrDrAsimm(ppcMulli, dReg, sReg2, immVal);
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createIrDrAsimm(ppcMulli, dReg, sReg1, immVal);
					} else {
						createIrDrArB(ppcMullw, dReg, sReg1, sReg2);
					}
					break;
				case tLong:
					createIrDrArB(ppcMullw, res.regAux1, opds[0].regLong, sReg2);
					createIrDrArB(ppcMullw, res.regAux2, sReg1, opds[1].regLong);
					createIrDrArB(ppcAdd, res.regAux1, res.regAux1, res.regAux2);
					createIrDrArB(ppcMulhwu, res.regAux2, sReg1, sReg2);
					createIrDrArB(ppcAdd, res.regLong, res.regAux1, res.regAux2);
					createIrDrArB(ppcMullw, res.reg, sReg1, sReg2);
					break;
				case tFloat:
					createIrDrArC(ppcFmuls, dReg, sReg1, sReg2);
					break;
				case tDouble:
					createIrDrArC(ppcFmul, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	//sCmul
			case sCdiv:
				opds = instr.getOperands();
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					createItrap(ppcTwi, TOifequal, opds[1].reg, 0);
					createIrDrArB(ppcDivw, res.reg, opds[0].reg, opds[1].reg);
					break;
				case tLong:
					createICRFrAsimm(ppcCmpi, CRF1, opds[1].regLong, -1); // is divisor negativ?
					createIBOBIBD(ppcBc, BOtrue, 4*CRF1+GT, 4);	
					createIrDrAsimm(ppcSubfic, res.regAux2, opds[1].reg, 0);	// negate divisor
					createIrDrA(ppcSubfze, res.regAux1, opds[1].regLong);
					createIBOBIBD(ppcBc, BOalways, 0, 3);
					createIrArSrB(ppcOr, res.regAux1, opds[1].regLong, opds[1].regLong); // copy if not negativ
					createIrArSrB(ppcOr, res.regAux2, opds[1].reg, opds[1].reg);
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux1, 0);	// test if divisor < 2^32
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 31);	// jump to label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0x7fff);	// test if divisor < 2^15
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 29);	// jump to label 1
					createIrDrArB(ppcDivw, res.regLong, opds[0].regLong, res.regAux2);
					createIrDrArB(ppcMullw, 0, res.regAux2, res.regLong);
					createIrDrArB(ppcSubf, 0, 0, opds[0].regLong);
					createICRFrAsimm(ppcCmpi, CRF0, 0, 0); // is remainder negativ?
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 3);	
					createIrDrArB(ppcAdd, 0, 0, res.regAux2);	// add divisor
					createIrDrAsimm(ppcAddi, res.regLong, res.regLong, -1);	
					createIrArSSHMBME(ppcRlwinm, res.regAux1, 0, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.regAux1, opds[0].reg, 16, 16, 31);
					createIrDrArB(ppcDivwu, res.reg, res.regAux1, res.regAux2);
					createIrDrArB(ppcMullw, 0, res.regAux2, res.reg);
					createIrDrArB(ppcSubf, 0, 0, res.regAux1);
					createIrArSSHMBME(ppcRlwinm, res.regAux1, 0, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.regAux1, opds[0].reg, 0, 16, 31);
					createIrDrArB(ppcDivwu, 0, res.regAux1, res.regAux2);
					createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.reg, 0, 0, 16, 31);
					createIrDrArB(ppcMullw, 0, res.regAux2, 0);
					createIrDrArB(ppcSubf, 0, 0, res.regAux1);
					createICRFrAsimm(ppcCmpi, CRF0, 0, 0); // is remainder > 0?
					createICRFrAsimm(ppcCmpi, CRF2, opds[0].regLong, 0); // and dividend negativ?
					createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0GT, CRF2LT);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 2);	
					createIrDrAsimm(ppcAddi, res.reg, res.reg, 1);	
					createIBOBIBD(ppcBc, BOtrue, 4*CRF1+GT, 3);	// was divisor negativ?
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);	// negate result
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					createIBOBIBD(ppcBc, BOalways, 0, 76);	// jump to end

					//label 1
					//TODO optimize load const
					Item item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[0].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux4, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux4, 0, res.regAux4);
					createIrSrAd(ppcStw, opds[0].reg, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, res.regAux4, res.regAux4, 0, res.regAux3);
					
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[1].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux5, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux5, 0, res.regAux5);
					createIrSrAd(ppcStw, opds[1].reg, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, res.regAux5, res.regAux5, 0, res.regAux3);

					createIrDrArB(ppcFdiv, res.regAux3, res.regAux4, res.regAux5);
					
					createIrSrAd(ppcStfd, res.regAux3, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset+4);
					createIrArSSHMBME(ppcRlwinm, res.regAux2, res.regAux1, 12, 21, 31);	
					createIrDrAsimm(ppcSubfic, res.regAux2, res.regAux2, 1075);	
					createICRFrAsimm(ppcCmpi, CRF2, res.regAux1, 0);
					createIrDrAsimm(ppcAddis, 0, 0, 0xfff0);	
					createIrArSrB(ppcAndc, res.regAux1, res.regAux1, 0);	
					createIrArSuimm(ppcOris, res.regAux1, res.regAux1, 0x10);	
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 52);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 4);	// jump to label 1
					createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIBOBIBD(ppcBc, BOalways, 0, 23);	// jump to end
					//label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0);
					createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 10);	// jump to label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSrw, res.reg, res.reg, res.regAux2);
					createIrArSrB(ppcSlw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, res.reg, res.reg, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSrw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, res.reg, res.reg, 0);
					createIrArSrB(ppcSrw, res.regLong, res.regAux1, res.regAux2);
					createIBOBIBD(ppcBc, BOalways, 0, 9);	// jump to label 3
					//label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSlw, res.regLong, res.regAux1, res.regAux2);
					createIrArSrB(ppcSrw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSlw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrArSrB(ppcSlw, res.reg, res.reg, res.regAux2);
					//label 3
					createIBOBIBD(ppcBc, BOfalse, 4*CRF2+LT, 3);	// jump to end
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					break;
				case tFloat:
					createIrDrArB(ppcFdivs, res.reg, opds[0].reg, opds[1].reg);
					break;
				case tDouble:
					createIrDrArB(ppcFdiv, res.reg, opds[0].reg, opds[1].reg);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCdiv
			case sCrem:
				opds = instr.getOperands();
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					createItrap(ppcTwi, TOifequal, opds[1].reg, 0);
					createIrDrArB(ppcDivw, 0, opds[0].reg, opds[1].reg);
					createIrDrArB(ppcMullw, 0, 0, opds[1].reg);
					createIrDrArB(ppcSubf, res.reg, 0 ,opds[0].reg);
					break;
				case tLong:
					createICRFrAsimm(ppcCmpi, CRF1, opds[1].regLong, -1); // is divisor negativ?
					createIBOBIBD(ppcBc, BOtrue, 4*CRF1+GT, 4);	
					createIrDrAsimm(ppcSubfic, res.regAux2, opds[1].reg, 0);	// negate divisor
					createIrDrA(ppcSubfze, res.regAux1, opds[1].regLong);
					createIBOBIBD(ppcBc, BOalways, 0, 3);
					createIrArSrB(ppcOr, res.regAux1, opds[1].regLong, opds[1].regLong); // copy if not negativ
					createIrArSrB(ppcOr, res.regAux2, opds[1].reg, opds[1].reg);
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux1, 0);	// test if divisor < 2^32
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 31);	// jump to label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0x7fff);	// test if divisor < 2^15
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 29);	// jump to label 1
					createIrDrArB(ppcDivw, res.regLong, opds[0].regLong, res.regAux2);
					createIrDrArB(ppcMullw, 0, res.regAux2, res.regLong);
					createIrDrArB(ppcSubf, 0, 0, opds[0].regLong);
					createICRFrAsimm(ppcCmpi, CRF0, 0, 0); // is remainder negativ?
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 3);	
					createIrDrArB(ppcAdd, 0, 0, res.regAux2);	// add divisor
					createIrDrAsimm(ppcAddi, res.regLong, res.regLong, -1);	
					createIrArSSHMBME(ppcRlwinm, res.regAux1, 0, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.regAux1, opds[0].reg, 16, 16, 31);
					createIrDrArB(ppcDivwu, res.reg, res.regAux1, res.regAux2);
					createIrDrArB(ppcMullw, 0, res.regAux2, res.reg);
					createIrDrArB(ppcSubf, 0, 0, res.regAux1);
					createIrArSSHMBME(ppcRlwinm, res.regAux1, 0, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.regAux1, opds[0].reg, 0, 16, 31);
					createIrDrArB(ppcDivwu, 0, res.regAux1, res.regAux2);
					createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 16, 0, 15);
					createIrArSSHMBME(ppcRlwimi, res.reg, 0, 0, 16, 31);
					createIrDrArB(ppcMullw, 0, res.regAux2, 0);
					createIrDrArB(ppcSubf, 0, 0, res.regAux1);
					createICRFrAsimm(ppcCmpi, CRF0, 0, 0); // is remainder > 0?
					createICRFrAsimm(ppcCmpi, CRF2, opds[0].regLong, 0); // and dividend negativ?
					createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0GT, CRF2LT);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 2);	
					createIrDrAsimm(ppcAddi, res.reg, res.reg, 1);	
					createIBOBIBD(ppcBc, BOtrue, 4*CRF1+GT, 3);	// was divisor negativ?
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);	// negate result
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					createIBOBIBD(ppcBc, BOalways, 0, 76);	// jump to label 4

					//label 1
					//TODO optimize load const
					Item item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[0].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux4, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux4, 0, res.regAux4);
					createIrSrAd(ppcStw, opds[0].reg, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, res.regAux4, res.regAux4, 0, res.regAux3);
					
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[1].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux5, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux5, 0, res.regAux5);
					createIrSrAd(ppcStw, opds[1].reg, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, res.regAux5, res.regAux5, 0, res.regAux3);

					createIrDrArB(ppcFdiv, res.regAux3, res.regAux4, res.regAux5);
					
					createIrSrAd(ppcStfd, res.regAux3, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset+4);
					createIrArSSHMBME(ppcRlwinm, res.regAux2, res.regAux1, 12, 21, 31);	
					createIrDrAsimm(ppcSubfic, res.regAux2, res.regAux2, 1075);	
					createICRFrAsimm(ppcCmpi, CRF2, res.regAux1, 0);
					createIrDrAsimm(ppcAddis, 0, 0, 0xfff0);	
					createIrArSrB(ppcAndc, res.regAux1, res.regAux1, 0);	
					createIrArSuimm(ppcOris, res.regAux1, res.regAux1, 0x10);	
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 52);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 4);	// jump to label 1
					createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIBOBIBD(ppcBc, BOalways, 0, 23);	// jump to label 4
					//label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0);
					createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 10);	// jump to label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSrw, res.reg, res.reg, res.regAux2);
					createIrArSrB(ppcSlw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, res.reg, res.reg, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSrw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, res.reg, res.reg, 0);
					createIrArSrB(ppcSrw, res.regLong, res.regAux1, res.regAux2);
					createIBOBIBD(ppcBc, BOalways, 0, 9);	// jump to label 3
					//label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSlw, res.regLong, res.regAux1, res.regAux2);
					createIrArSrB(ppcSrw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSlw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrArSrB(ppcSlw, res.reg, res.reg, res.regAux2);
					//label 3
					createIBOBIBD(ppcBc, BOfalse, 4*CRF2+LT, 3);	// jump to label 4
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					//label 4
					createIrDrArB(ppcMullw, res.regAux1, res.regLong, opds[1].reg);
					createIrDrArB(ppcMullw, res.regAux2, res.reg, opds[1].regLong);
					createIrDrArB(ppcAdd, res.regAux1, res.regAux1, res.regAux2);
					createIrDrArB(ppcMulhwu, res.regAux2, res.reg, opds[1].reg);
					createIrDrArB(ppcAdd, res.regLong, res.regAux1, res.regAux2);
					createIrDrArB(ppcMullw, res.reg, res.reg, opds[1].reg);
					
					createIrDrArB(ppcSubfc, res.reg, res.reg, opds[0].reg);
					createIrDrArB(ppcSubfe, res.regLong, res.regLong, opds[0].regLong);				
					break;
				case tFloat:	// correct if a / b < 32 bit
					createIrDrArB(ppcFdiv, res.reg, opds[0].reg, opds[1].reg);
					createIrDrB(ppcFctiwz, 0, res.reg);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset + 4);
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, res.regAux1, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.reg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.reg, 0, res.reg);
					createIrDrArC(ppcFmul, res.reg, res.reg, opds[1].reg);
					createIrDrArB(ppcFsub, res.reg, opds[0].reg, res.reg);
					break;
				case tDouble:	// correct if a / b < 32 bit
					createIrDrArB(ppcFdiv, res.reg, opds[0].reg, opds[1].reg);
					createIrDrB(ppcFctiwz, 0, res.reg);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset + 4);
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, res.regAux1, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.reg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.reg, 0, res.reg);
					createIrDrArC(ppcFmul, res.reg, res.reg, opds[1].reg);
					createIrDrArB(ppcFsub, res.reg, opds[0].reg, res.reg);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCrem
			case sCneg:
				opds = instr.getOperands();
				type = res.type & ~(1<<ssaTaFitIntoInt);
				if (type == tInteger)
					createIrDrA(ppcNeg, res.reg, opds[0].reg);
				else if (type == tLong) {
					createIrDrAsimm(ppcSubfic, res.reg, opds[0].reg, 0);
					createIrDrA(ppcSubfze, res.regLong, opds[0].regLong);
				} else if (type == tFloat || type == tDouble)
					createIrDrB(ppcFneg, res.reg, opds[0].reg);
				else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCneg
			case sCshl:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				type = res.type & ~(1<<ssaTaFitIntoInt);
				if (type == tInteger) {
					if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
						createIrArSSHMBME(ppcRlwinm, dReg, sReg1, immVal, 0, 31-immVal);
					} else {
						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 27, 31);
						createIrArSrB(ppcSlw, dReg, sReg1, 0);
					}
				} else if (type == tLong) {
					if (sReg2 < 0) {	
						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
						if (immVal < 32) {
							createIrArSSHMBME(ppcRlwinm, res.regLong, sReg1, immVal, 32-immVal, 31);
							createIrArSSHMBME(ppcRlwimi, res.regLong, opds[0].regLong, immVal, 0, 31-immVal);
							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, immVal, 0, 31-immVal);
						} else {
							createIrDrAsimm(ppcAddi, dReg, 0, 0);
							createIrArSSHMBME(ppcRlwinm, res.regLong, sReg1, immVal-32, 0, 63-immVal);
						}
					} else { 
						createIrDrAsimm(ppcSubfic, res.regAux1, sReg2, 32);
						createIrArSrB(ppcSlw, res.regLong, opds[0].regLong, sReg2);
						createIrArSrB(ppcSrw, 0, sReg1, res.regAux1);
						createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
						createIrDrAsimm(ppcAddi, res.regAux1, sReg2, -32);
						createIrArSrB(ppcSlw, 0, sReg1, res.regAux1);
						createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
						createIrArSrB(ppcSlw, dReg, sReg1, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCshl
			case sCshr:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				type = res.type & ~(1<<ssaTaFitIntoInt);
				if (type == tInteger) {
					if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
						createIrArSSH(ppcSrawi, dReg, sReg1, immVal);
					} else {
						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 27, 31);
						createIrArSrB(ppcSraw, dReg, sReg1, 0);
					}
				} else if (type == tLong) {
					if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
						if (immVal == 0) {
							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
						} else if (immVal < 32) {
							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 32-immVal, immVal, 31);
							createIrArSSHMBME(ppcRlwimi, dReg, opds[0].regLong, 32-immVal, 0, immVal-1);
							createIrArSSH(ppcSrawi, res.regLong, opds[0].regLong, immVal);
						} else {
							immVal %= 32;
							createIrArSSH(ppcSrawi, res.reg, opds[0].regLong, immVal);
							createIrArSSH(ppcSrawi, res.regLong, opds[0].regLong, 31);
						}
					} else {
						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 26, 31);
						createIrDrAsimm(ppcSubfic, res.regAux1, 0, 32);
						createIrArSrB(ppcSrw, dReg, sReg1, sReg2);
						createIrArSrB(ppcSlw, 0, opds[0].regLong, res.regAux1);
						createIrArSrB(ppcOr, dReg, dReg, 0);
						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 26, 31);
						createIrDrAsimm(ppcAddicp, res.regAux1, 0, -32);
						createIrArSrB(ppcSraw, 0, opds[0].regLong, res.regAux1);
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 2);
						createIrArSuimm(ppcOri, dReg, 0, 0);
						createIrArSrB(ppcSraw, res.regLong, opds[0].regLong, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCshr
			case sCushr:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				type = res.type & ~(1<<ssaTaFitIntoInt);
				if (type == tInteger) {
					if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
						createIrArSSHMBME(ppcRlwinm, dReg, sReg1, (32-immVal)%32, immVal, 31);
					} else {
						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 27, 31);
						createIrArSrB(ppcSrw, dReg, sReg1, 0);
					}
				} else if (type == tLong) {
					if (sReg2 < 0) {	
						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
						if (immVal == 0) {
							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
						} else if (immVal < 32) {
							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, (32-immVal)%32, immVal, 31);
							createIrArSSHMBME(ppcRlwimi, dReg, opds[0].regLong, (32-immVal)%32, 0, (immVal-1)&0x1f);
							createIrArSSHMBME(ppcRlwinm, res.regLong, opds[0].regLong, (32-immVal)%32, immVal, 31);
						} else {
							createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
							createIrArSSHMBME(ppcRlwinm, dReg, opds[0].regLong, (64-immVal)%32, immVal-32, 31);
						}
					} else {
						createIrDrAsimm(ppcSubfic, res.regAux1, sReg2, 32);
						createIrArSrB(ppcSrw, dReg, sReg1, sReg2);
						createIrArSrB(ppcSlw, 0, opds[0].regLong, res.regAux1);
						createIrArSrB(ppcOr, dReg, dReg, 0);
						createIrDrAsimm(ppcAddi, res.regAux1, sReg2, -32);
						createIrArSrB(ppcSrw, 0, opds[0].regLong, res.regAux1);
						createIrArSrB(ppcOr, dReg, dReg, 0);
						createIrArSrB(ppcSrw, res.regLong, opds[0].regLong, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCushr
			case sCand:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				if ((res.type & ~(1<<ssaTaFitIntoInt)) == tInteger) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						if (immVal >= 0)
							createIrArSuimm(ppcAndi, dReg, sReg2, immVal);
						else {
							createIrDrAsimm(ppcAddi, 0, 0, immVal);
							createIrArSrB(ppcAnd, dReg, 0, sReg2);
						}
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						if (immVal >= 0)
							createIrArSuimm(ppcAndi, dReg, sReg1, immVal);
						else {
							createIrDrAsimm(ppcAddi, 0, 0, immVal);
							createIrArSrB(ppcAnd, dReg, 0, sReg1);
						}
					} else
						createIrArSrB(ppcAnd, dReg, sReg1, sReg2);
				} else if ((res.type & ~(1<<ssaTaFitIntoInt)) == tLong) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueL;
						if (immVal >= 0) {
							createIrArSuimm(ppcAndi, res.regLong, opds[1].regLong, 0);
							createIrArSuimm(ppcAndi, dReg, sReg2, (int)immVal);
						} else {
							createIrDrAsimm(ppcAddi, 0, 0, immVal);
							createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
							createIrArSrB(ppcAnd, dReg, sReg2, 0);
						}
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueL;
						if (immVal >= 0) {
							createIrArSuimm(ppcAndi, res.regLong, opds[0].regLong, 0);
							createIrArSuimm(ppcAndi, dReg, sReg1, (int)immVal);
						} else {
							createIrDrAsimm(ppcAddi, 0, 0, immVal);
							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
							createIrArSrB(ppcAnd, dReg, sReg1, 0);
						}
					} else {
						createIrArSrB(ppcAnd, res.regLong, opds[0].regLong, opds[1].regLong);
						createIrArSrB(ppcAnd, dReg, sReg1, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
			break;	// sCand
			case sCor:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				if ((res.type & ~(1<<ssaTaFitIntoInt)) == tInteger) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createIrArSuimm(ppcOri, dReg, sReg2, immVal);
						if (immVal < 0)
							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);					
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createIrArSuimm(ppcOri, dReg, sReg1, immVal);
						if (immVal < 0)
							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);					
					} else
						createIrArSrB(ppcOr, dReg, sReg1, sReg2);
				} else if ((res.type & ~(1<<ssaTaFitIntoInt)) == tLong) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueL;
						createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
						createIrArSuimm(ppcOri, dReg, sReg2, (int)immVal);
						if (immVal < 0) {
							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);	
							createIrDrAsimm(ppcAddi, res.regLong, 0, -1);	
						}
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueL;
						createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
						createIrArSuimm(ppcOri, dReg, sReg1, (int)immVal);
						if (immVal < 0) {
							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);					
							createIrDrAsimm(ppcAddi, res.regLong, 0, -1);	
						}
					} else {
						createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[1].regLong);
						createIrArSrB(ppcOr, dReg, sReg1, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	//sCor
			case sCxor:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				if ((res.type & ~(1<<ssaTaFitIntoInt)) == tInteger) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createIrArSuimm(ppcXori, dReg, sReg2, immVal);
						if (immVal < 0)
							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
						else 
							createIrArSuimm(ppcXoris, dReg, dReg, 0);
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createIrArSuimm(ppcXori, dReg, sReg1, immVal);
						if (immVal < 0)
							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
						else 
							createIrArSuimm(ppcXoris, dReg, dReg, 0);
					} else
						createIrArSrB(ppcXor, dReg, sReg1, sReg2);
				} else if ((res.type & ~(1<<ssaTaFitIntoInt)) == tLong) {
					if (sReg1 < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueL;
						createIrArSuimm(ppcXori, dReg, sReg2, (int)immVal);
						if (immVal < 0) {
							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
							createIrArSuimm(ppcXori, res.regLong, opds[1].regLong, 0xffff);
							createIrArSuimm(ppcXoris, res.regLong, res.regLong, 0xffff);
						} else {
							createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
							createIrArSuimm(ppcXoris, dReg, dReg, 0);
						}
					} else if (sReg2 < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueL;
						createIrArSuimm(ppcXori, dReg, sReg1, (int)immVal);
						if (immVal < 0) {
							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
							createIrArSuimm(ppcXori, res.regLong, opds[0].regLong, 0xffff);
							createIrArSuimm(ppcXoris, res.regLong, res.regLong, 0xffff);
						} else {
							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
							createIrArSuimm(ppcXoris, dReg, dReg, 0);
						}
					} else {
						createIrArSrB(ppcXor, res.regLong, opds[0].regLong, opds[1].regLong);
						createIrArSrB(ppcXor, dReg, sReg1, sReg2);
					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCxor
			case sCconvInt:	// int -> other type
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte:
					createIrArS(ppcExtsb, dReg, sReg1);
					break;
				case tChar: 
					createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 0, 16, 31);
					break;
				case tShort: 
					createIrArS(ppcExtsh, dReg, sReg1);
					break;
				case tLong:
					createIrArSrB(ppcOr, dReg, sReg1, sReg1);
					createIrArSSH(ppcSrawi, res.regLong, sReg1, 31);
					break;
				case tFloat:
					Item item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, sReg1, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, dReg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, dReg, 0, dReg);
					createIrDrB(ppcFrsp, dReg, dReg);
					break;
				case tDouble:
//					instructions[iCount] = ppcMtfsfi | (7 << 23) | (4  << 12);
//					incInstructionNum();
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, sReg1, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, dReg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, dReg, 0, dReg);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;	// sCconvInt
			case sCconvLong:	// long -> other type
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)){
				case tByte:
					createIrArS(ppcExtsb, dReg, sReg1);
					break;
				case tChar: 
					createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 0, 16, 31);
					break;
				case tShort: 
					createIrArS(ppcExtsh, dReg, sReg1);
					break;
				case tInteger:
					createIrArSrB(ppcOr, dReg, sReg1, sReg1);
					break;
				case tFloat:
					//TODO optimize load const
					Item item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[0].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, dReg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, dReg, 0, dReg);
					createIrSrAd(ppcStw, sReg1, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, dReg, dReg, 0, res.regAux3);
					createIrDrB(ppcFrsp, dReg, dReg);
					break;
				case tDouble:
					//TODO optimize load const
					item = int2floatConst1;	// ref to 2^52+2^31;					
					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
					createIrArSuimm(ppcXoris, 0, opds[0].regLong, 0x8000);
					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, dReg, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, dReg, 0, dReg);
					createIrSrAd(ppcStw, sReg1, stackPtr, tempStorageOffset+4);
					item = int2floatConst3;	// ref to 2^52;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, res.regAux3, res.regAux1, 0);
					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
					createIrDrArB(ppcFsub, res.regAux3, 0, res.regAux3);					
					item = int2floatConst2;	// ref to 2^32;
					loadConstantAndFixup(res.regAux1, item);
					createIrDrAd(ppcLfd, 0, res.regAux1, 0);
					createIrDrArCrB(ppcFmadd, dReg, dReg, 0, res.regAux3);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;
			case sCconvFloat:	// float -> other type
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte:
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArS(ppcExtsb, dReg, 0);
					break;
				case tChar: 
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArSSHMBME(ppcRlwinm, dReg, 0, 0, 16, 31);
					break;
				case tShort: 
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArS(ppcExtsh, dReg, 0);
					break;
				case tInteger:
					createIrDrB(ppcFctiwz, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, dReg, stackPtr, tempStorageOffset + 4);
					break;
				case tLong:	
					createIrSrAd(ppcStfd, sReg1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset+4);
					createIrArSSHMBME(ppcRlwinm, res.regAux2, res.regAux1, 12, 21, 31);	
					createIrDrAsimm(ppcSubfic, res.regAux2, res.regAux2, 1075);	
					createICRFrAsimm(ppcCmpi, CRF2, res.regAux1, 0);
					createIrDrAsimm(ppcAddis, 0, 0, 0xfff0);	
					createIrArSrB(ppcAndc, res.regAux1, res.regAux1, 0);	
					createIrArSuimm(ppcOris, res.regAux1, res.regAux1, 0x10);	
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 52);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 4);	// jump to label 1
					createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIBOBIBD(ppcBc, BOalways, 0, 34);	// jump to end
					//label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0);
					createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 10);	// jump to label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSrw, res.reg, res.reg, res.regAux2);
					createIrArSrB(ppcSlw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, dReg, dReg, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSrw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, dReg, dReg, 0);
					createIrArSrB(ppcSrw, res.regLong, res.regAux1, res.regAux2);
					createIBOBIBD(ppcBc, BOalways, 0, 20);	// jump to label 5
					//label 2
					createIrDrA(ppcNeg, res.regAux2, res.regAux2);
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 11);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 9);	// jump to label 4
					createIBOBIBD(ppcBc, BOtrue, 4*CRF2+LT, 5);	// jump to label 3
					createIrDrAsimm(ppcAddi, res.reg, 0, -1);
					createIrDrAsimm(ppcAddis, res.regLong, 0, 0x7fff);
					createIrArSuimm(ppcOri, res.regLong, res.regLong, 0xffff);
					createIBOBIBD(ppcBc, BOalways, 0, 15);	// jump to end
					//label 3
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIrDrAsimm(ppcAddis, res.regLong, 0, 0x8000);
					createIBOBIBD(ppcBc, BOalways, 0, 12);	// jump to end
					//label 4
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSlw, res.regLong, res.regAux1, res.regAux2);
					createIrArSrB(ppcSrw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSlw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrArSrB(ppcSlw, res.reg, res.reg, res.regAux2);
					//label 5
					createIBOBIBD(ppcBc, BOfalse, 4*CRF2+LT, 3);	// jump to end
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					break;
				case tDouble:
					createIrDrB(ppcFmr, dReg, sReg1);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;
			case sCconvDouble:	// double -> other type
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				dReg = res.reg;
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte:
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArS(ppcExtsb, dReg, 0);
					break;
				case tChar: 
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArSSHMBME(ppcRlwinm, dReg, 0, 0, 16, 31);
					break;
				case tShort: 
					createIrDrB(ppcFctiw, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
					createIrArS(ppcExtsh, dReg, 0);
					break;
				case tInteger:
					createIrDrB(ppcFctiwz, 0, sReg1);
					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, dReg, stackPtr, tempStorageOffset + 4);
					break;
				case tLong:	
					createIrSrAd(ppcStfd, sReg1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.regAux1, stackPtr, tempStorageOffset);
					createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset+4);
					createIrArSSHMBME(ppcRlwinm, res.regAux2, res.regAux1, 12, 21, 31);	
					createIrDrAsimm(ppcSubfic, res.regAux2, res.regAux2, 1075);	
					createICRFrAsimm(ppcCmpi, CRF2, res.regAux1, 0);
					createIrDrAsimm(ppcAddis, 0, 0, 0xfff0);	
					createIrArSrB(ppcAndc, res.regAux1, res.regAux1, 0);	
					createIrArSuimm(ppcOris, res.regAux1, res.regAux1, 0x10);	
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 52);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 4);	// jump to label 1
					createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIBOBIBD(ppcBc, BOalways, 0, 34);	// jump to end
					//label 1
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 0);
					createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 10);	// jump to label 2
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSrw, res.reg, res.reg, res.regAux2);
					createIrArSrB(ppcSlw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, dReg, dReg, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSrw, 0, res.regAux1, 0);
					createIrArSrB(ppcOr, dReg, dReg, 0);
					createIrArSrB(ppcSrw, res.regLong, res.regAux1, res.regAux2);
					createIBOBIBD(ppcBc, BOalways, 0, 20);	// jump to label 5
					//label 2
					createIrDrA(ppcNeg, res.regAux2, res.regAux2);
					createICRFrAsimm(ppcCmpi, CRF0, res.regAux2, 11);
					createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 9);	// jump to label 4
					createIBOBIBD(ppcBc, BOtrue, 4*CRF2+LT, 5);	// jump to label 3
					createIrDrAsimm(ppcAddi, res.reg, 0, -1);
					createIrDrAsimm(ppcAddis, res.regLong, 0, 0x7fff);
					createIrArSuimm(ppcOri, res.regLong, res.regLong, 0xffff);
					createIBOBIBD(ppcBc, BOalways, 0, 15);	// jump to end
					//label 3
					createIrDrAsimm(ppcAddi, res.reg, 0, 0);
					createIrDrAsimm(ppcAddis, res.regLong, 0, 0x8000);
					createIBOBIBD(ppcBc, BOalways, 0, 12);	// jump to end
					//label 4
					createIrDrAsimm(ppcSubfic, 0, res.regAux2, 32);
					createIrArSrB(ppcSlw, res.regLong, res.regAux1, res.regAux2);
					createIrArSrB(ppcSrw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrDrAsimm(ppcAddi, 0, res.regAux2, -32);
					createIrArSrB(ppcSlw, 0, res.reg, 0);
					createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
					createIrArSrB(ppcSlw, res.reg, res.reg, res.regAux2);
					//label 5
					createIBOBIBD(ppcBc, BOfalse, 4*CRF2+LT, 3);	// jump to end
					createIrDrAsimm(ppcSubfic, res.reg, res.reg, 0);
					createIrDrA(ppcSubfze, res.regLong, res.regLong);
					break;
				case tFloat:
					createIrDrB(ppcFrsp, dReg, sReg1);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;
			case sCcmpl: case sCcmpg:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				sReg2 = opds[1].reg;
				dReg = res.reg;
				type = opds[0].type & ~(1<<ssaTaFitIntoInt);
				if (type == tLong) {
					int sReg1L = opds[0].regLong;
					int sReg2L = opds[1].regLong;
					createICRFrArB(ppcCmp, CRF0, sReg1L, sReg2L);
					createICRFrArB(ppcCmpl, CRF1, sReg1, sReg2);
					instr = node.instructions[i+1];
					if (instr.ssaOpcode == sCregMove) {i++; instr = node.instructions[i+1]; assert false;}
					assert instr.ssaOpcode == sCbranch : "sCcompl or sCcompg is not followed by branch instruction";
					bci = ssa.cfg.code[node.lastBCA] & 0xff;
					if (bci == bCifeq) {
						createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0EQ, CRF1EQ);
						createIBOBIBD(ppcBc, BOtrue, CRF0EQ, 0);
					} else if (bci == bCifne) {
						createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0EQ, CRF1EQ);
						createIBOBIBD(ppcBc, BOfalse, CRF0EQ, 0);
					} else if (bci == bCiflt) {
						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1LT);
						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0LT);
						createIBOBIBD(ppcBc, BOtrue, CRF0LT, 0);
					} else if (bci == bCifge) {
						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1LT);
						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0LT);
						createIBOBIBD(ppcBc, BOfalse, CRF0LT, 0);
					} else if (bci == bCifgt) {
						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1GT);
						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0GT);
						createIBOBIBD(ppcBc, BOtrue, CRF0LT, 0);
					} else if (bci == bCifle) {
						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1GT);
						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0GT);
						createIBOBIBD(ppcBc, BOfalse, CRF0LT, 0);
					} else {
						ErrorReporter.reporter.error(623);
						assert false : "sCcompl or sCcompg is not followed by branch instruction";
						return;
					}
				} else if (type == tFloat  || type == tDouble) {
					createICRFrArB(ppcFcmpu, CRF1, sReg1, sReg2);
					instr = node.instructions[i+1];
					assert instr.ssaOpcode == sCbranch : "sCcompl or sCcompg is not followed by branch instruction";
					bci = ssa.cfg.code[node.lastBCA] & 0xff;
					if (bci == bCifeq) 
						createIBOBIBD(ppcBc, BOtrue, CRF1EQ, 0);
					else if (bci == bCifne)
						createIBOBIBD(ppcBc, BOfalse, CRF1EQ, 0);
					else if (bci == bCiflt)
						createIBOBIBD(ppcBc, BOtrue, CRF1LT, 0);
					else if (bci == bCifge)
						createIBOBIBD(ppcBc, BOfalse, CRF1LT, 0);
					else if (bci == bCifgt)
						createIBOBIBD(ppcBc, BOtrue, CRF1GT, 0);
					else if (bci == bCifle)
						createIBOBIBD(ppcBc, BOfalse, CRF1GT, 0);
					else {
						ErrorReporter.reporter.error(623);
						assert false : "sCcompl or sCcompg is not followed by branch instruction";
						return;
					}
				} else {
					ErrorReporter.reporter.error(611);
					assert false : "operand of SSA instruction has wrong type";
					return;
				}
				i++;
				break;
			case sCinstanceof:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;

				MonadicRef ref = (MonadicRef)instr;
				Type t = (Type)ref.item;
				if (t instanceof Class) offset = ((Class)t).extensionLevel;
				else offset = 1;	// object is an array
				createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);
				createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 1
				createIrDrAsimm(ppcAddi, res.reg, 0, 0);
				createIBOBIBD(ppcBc, BOalways, 4*CRF0, 7);	// jump to end
				// label 1
				createIrDrAd(ppcLwz, res.regAux1, sReg1, -4);
				createIrDrAd(ppcLwz, 0, res.regAux1, 8 + offset * 4);
				loadConstantAndFixup(res.regAux1, t);	// addr of type
				createICRFrArB(ppcCmpl, CRF0, 0, res.regAux1);
				createIrD(ppcMfcr, res.reg);
				createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);
				break;
			case sCalength:
				opds = instr.getOperands();
				refReg = opds[0].reg;
				createItrap(ppcTwi, TOifequal, refReg, 0);
				createIrDrAd(ppcLha, res.reg , refReg, -arrayLenOffset);
				break;
			case sCcall:
				opds = instr.getOperands();
				Call call = (Call)instr;
				if ((call.item.accAndPropFlags & (1 << dpfSynthetic)) != 0) {
					if ((call.item.accAndPropFlags & sysMethCodeMask) == idGET1) {	//GET1
						createIrDrAd(ppcLbz, res.reg, opds[0].reg, 0);
						createIrArS(ppcExtsb, res.reg, res.reg);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGET2) { // GET2
						createIrDrAd(ppcLha, res.reg, opds[0].reg, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGET4) { // GET4
						createIrDrAd(ppcLwz, res.reg, opds[0].reg, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGET8) { // GET8
						createIrDrAd(ppcLwz, res.regLong, opds[0].reg, 0);
						createIrDrAd(ppcLwz, res.reg, opds[0].reg, 4);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUT1) { // PUT1
						createIrSrAd(ppcStb, opds[1].reg, opds[0].reg, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUT2) { // PUT2
						createIrSrAd(ppcSth, opds[1].reg, opds[0].reg, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUT4) { // PUT4
						createIrSrAd(ppcStw, opds[1].reg, opds[0].reg, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUT8) { // PUT8
						createIrSrAd(ppcStw, opds[1].regLong, opds[0].reg, 0);
						createIrSrAd(ppcStw, opds[1].reg, opds[0].reg, 4);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idBIT) { // BIT
						createIrDrAd(ppcLbz, res.reg, opds[0].reg, 0);
						createIrDrAsimm(ppcSubfic, 0, opds[1].reg, 32);
						createIrArSrBMBME(ppcRlwnm, res.reg, res.reg, 0, 31, 31);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGETGPR) { // GETGPR
						int gpr = ((StdConstant)opds[0].constant).valueH;
						createIrArSrB(ppcOr, res.reg, gpr, gpr);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGETFPR) { // GETFPR
						int fpr = ((StdConstant)opds[0].constant).valueH;
						createIrDrB(ppcFmr, res.reg, fpr);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idGETSPR) { // GETSPR
						int spr = ((StdConstant)opds[0].constant).valueH;
						createIrSspr(ppcMfspr, spr, res.reg);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUTGPR) { // PUTGPR
						int gpr = ((StdConstant)opds[0].constant).valueH;
						createIrArSrB(ppcOr, gpr, opds[1].reg, opds[1].reg);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUTFPR) { // PUTFPR
						int fpr = ((StdConstant)opds[0].constant).valueH;
						createIrDrB(ppcFmr, fpr, opds[1].reg);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idPUTSPR) { // PUTSPR
						createIrArSrB(ppcOr, 0, opds[1].reg, opds[1].reg);
						int spr = ((StdConstant)opds[0].constant).valueH;
						createIrSspr(ppcMtspr, spr, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idHALT) { // HALT
						createItrap(ppcTw, TOalways, 0, 0);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idASM) { // ASM
						instructions[iCount] = InstructionDecoder.getCode(((StringLiteral)opds[0].constant).string.toString());
						iCount++;
						int len = instructions.length;
						if (iCount == len) {
							int[] newInstructions = new int[2 * len];
							for (int k = 0; k < len; k++)
								newInstructions[k] = instructions[k];
							instructions = newInstructions;
						}
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idADR_OF_METHOD) { // ADR_OF_METHOD
						HString name = ((StringLiteral)opds[0].constant).string;
						int last = name.lastIndexOf('/');
						HString className = name.substring(0, last);
						HString methName = name.substring(last + 1);
						Class clazz = (Class)(Type.classList.getItemByName(className.toString()));
						Item method = clazz.methods.getItemByName(methName.toString());
						loadConstantAndFixup(res.reg, method);	// addr of method
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idDoubleToBits) { // DoubleToBits
						createIrSrAd(ppcStfd, opds[0].reg, stackPtr, tempStorageOffset);
						createIrDrAd(ppcLwz, res.regLong, stackPtr, tempStorageOffset);
						createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset + 4);
					} else if ((call.item.accAndPropFlags & sysMethCodeMask) == idBitsToDouble) { // BitsToDouble
						createIrSrAd(ppcStw, opds[0].regLong, stackPtr, tempStorageOffset);
						createIrSrAd(ppcStw, opds[0].reg, stackPtr, tempStorageOffset+4);
						createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
					}
				} else {	// no synthetic method
					if ((call.item.accAndPropFlags & (1<<apfStatic)) != 0) {	// invokestatic
						if (call.item == stringNewstringMethod) {	// replace newstring stub with Heap.newstring
							call.item = heapNewstringMethod;
							loadConstantAndFixup(res.regAux1, call.item);	
							createIrSspr(ppcMtspr, LR, res.regAux1); 
						} else {
							loadConstantAndFixup(res.regAux1, call.item);	// addr of method
							createIrSspr(ppcMtspr, LR, res.regAux1);
						}
					} else if ((call.item.accAndPropFlags & (1<<dpfInterfCall)) != 0) {	// invokeinterface
						refReg = opds[0].reg;
						offset = call.item.index;
						createItrap(ppcTwi, TOifequal, refReg, 0);
						createIrDrAd(ppcLwz, res.regAux1, refReg, -4);
						createIrDrAd(ppcLwz, res.regAux1, res.regAux1, -offset);
						createIrDrAd(ppcLwz, res.regAux1, res.regAux1, 0);
						createIrSspr(ppcMtspr, LR, res.regAux1);
					} else if (call.invokespecial) {	// invokespecial
						if (newString) {	// special treatment for strings
							if (call.item == strInitC) call.item = strAllocC;
							else if (call.item == strInitCII) call.item = strAllocCII;	// addr of corresponding allocate method
							else if (call.item == strInitCII) call.item = strAllocCII;
							loadConstantAndFixup(res.regAux1, call.item);	
							createIrSspr(ppcMtspr, LR, res.regAux1);
						} else {
							refReg = opds[0].reg;
							createItrap(ppcTwi, TOifequal, refReg, 0);
							loadConstantAndFixup(res.regAux1, call.item);	// addr of init method
							createIrSspr(ppcMtspr, LR, res.regAux1);
						}
					} else {	// invokevirtual 
						refReg = opds[0].reg;
//						offset = Linker.cdInterface0AddrOffset + ((Method)call.item).owner.nofInterfaces * Linker.slotSize; // TODO @ Urs implement this
						offset = Linker32.tdInterface0AddrOffset;
						offset += call.item.index * Linker32.slotSize; 
						createItrap(ppcTwi, TOifequal, refReg, 0);
						createIrDrAd(ppcLwz, res.regAux1, refReg, -4);
						createIrDrAd(ppcLwz, res.regAux1, res.regAux1, -offset);
						createIrSspr(ppcMtspr, LR, res.regAux1);
					}
					
					// copy parameters into registers and to stack if not enough registers
					if (dbg) StdStreams.vrb.println("call to " + call.item.name + ": copy parameters");
					copyParameters(opds);
					
					if (newString) {
						int sizeOfObject = Type.wktObject.getObjectSize();
						createIrDrAsimm(ppcAddi, paramStartGPR+opds.length, 0, sizeOfObject); // reg after last parameter
					}
					createIBOBILK(ppcBclr, BOalways, 0, true);
					
					// get result
					type = res.type & ~(1<<ssaTaFitIntoInt);
					if (type == tLong) {
						if (res.regLong == returnGPR2) {
							if (res.reg == returnGPR1) {	// returnGPR2 -> r0, returnGPR1 -> r3, r0 -> r2
								createIrArSrB(ppcOr, 0, returnGPR2, returnGPR2);
								createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
								createIrArSrB(ppcOr, res.reg, 0, 0);
							} else {	// returnGPR2 -> reg, returnGPR1 -> r3
								createIrArSrB(ppcOr, res.reg, returnGPR2, returnGPR2);
								createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
							}
						} else { // returnGPR1 -> regLong, returnGPR2 -> reg
							createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
							createIrArSrB(ppcOr, res.reg, returnGPR2, returnGPR2);
						}
					} else if (type == tFloat || type == tDouble) {
						createIrDrB(ppcFmr, res.reg, returnFPR);
					} else if (type == tVoid) {
						if (newString) {
							newString = false;
							createIrArSrB(ppcOr, strReg, returnGPR1, returnGPR1);
						}
					} else
						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
					
				}
				break;	//sCcall
			case sCnew:
				opds = instr.getOperands();
				Item item = ((Call)instr).item;	// item = ref
				Item method;
				if (opds == null) {	// bCnew
					if (item == Type.wktString) {
						newString = true;	// allocation of strings is postponed
						strReg = res.reg;
						loadConstantAndFixup(res.reg, item);	// ref to string
					} else {
						method = Class.getNewMemoryMethod(bCnew);
						loadConstantAndFixup(paramStartGPR, method);	// addr of new
						createIrSspr(ppcMtspr, LR, paramStartGPR);
						loadConstantAndFixup(paramStartGPR, item);	// ref
						createIBOBILK(ppcBclr, BOalways, 0, true);
						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
					}
				} else if (opds.length == 1) {
					switch (res.type  & ~(1<<ssaTaFitIntoInt)) {
					case tAboolean: case tAchar: case tAfloat: case tAdouble:
					case tAbyte: case tAshort: case tAinteger: case tAlong:	// bCnewarray
						method = Class.getNewMemoryMethod(bCnewarray);
						loadConstantAndFixup(res.regAux1, method);	// addr of newarray
						createIrSspr(ppcMtspr, LR, res.regAux1);
						createIrArSrB(ppcOr, paramStartGPR, opds[0].reg, opds[0].reg);	// nof elems
						createItrapSimm(ppcTwi, TOifless, paramStartGPR, 0);
						createIrDrAsimm(ppcAddi, paramStartGPR + 1, 0, (instr.result.type & 0x7fffffff) - 10);	// type
						loadConstantAndFixup(paramStartGPR + 2, item);	// ref to type descriptor
						createIBOBILK(ppcBclr, BOalways, 0, true);
						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
						break;
					case tAref:	// bCanewarray
						method = Class.getNewMemoryMethod(bCanewarray);
						loadConstantAndFixup(res.regAux1, method);	// addr of anewarray
						createIrSspr(ppcMtspr, LR, res.regAux1);
						createIrArSrB(ppcOr, paramStartGPR, opds[0].reg, opds[0].reg);	// nof elems
						createItrapSimm(ppcTwi, TOifless, paramStartGPR, 0);
						loadConstantAndFixup(paramStartGPR + 1, item);	// ref to type descriptor
						createIBOBILK(ppcBclr, BOalways, 0, true);
						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
						break;
					default:
						ErrorReporter.reporter.error(612);
						assert false : "operand of new instruction has wrong type";
						return;
					}
				} else { // bCmultianewarray:
					method = Class.getNewMemoryMethod(bCmultianewarray);
					loadConstantAndFixup(res.regAux1, method);	// addr of multianewarray
					createIrSspr(ppcMtspr, LR, res.regAux1);
					// copy dimensions
					offset = 0;
					for (int k = 0; k < nofGPR; k++) {srcGPR[k] = 0; srcGPRcount[k] = 0;}

					// get info about in which register parameters are located
					// the first two parameter registers are used for nofDim and ref
					// therefore start is at paramStartGPR + 2
					for (int k = 0, kGPR = 0; k < opds.length; k++) {
						type = opds[k].type & ~(1<<ssaTaFitIntoInt);
						if (type == tLong) {
							srcGPR[kGPR + paramStartGPR + 2] = opds[k].regLong;	
							srcGPR[kGPR + 1 + paramStartGPR + 2] = opds[k].reg;
							kGPR += 2;
						} else {
							srcGPR[kGPR + paramStartGPR + 2] = opds[k].reg;
							kGPR++;
						}
					}
					
					// count register usage
					int cnt = paramStartGPR + 2;
					while (srcGPR[cnt] != 0) srcGPRcount[srcGPR[cnt++]]++;
					
					// handle move to itself
					cnt = paramStartGPR + 2;
					while (srcGPR[cnt] != 0) {
						if (srcGPR[cnt] == cnt) srcGPRcount[cnt]--;
						cnt++;
					}

					// move registers 
					boolean done = false;
					while (!done) {
						cnt = paramStartGPR + 2; done = true;
						while (srcGPR[cnt] != 0) {
							if (srcGPRcount[cnt] == 0) { // check if register no longer used for parameter
								if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (cnt-paramStartGPR) + " from register " + srcGPR[cnt] + " to " + cnt);
								createIrArSrB(ppcOr, cnt, srcGPR[cnt], srcGPR[cnt]);
								srcGPRcount[cnt]--; srcGPRcount[srcGPR[cnt]]--; 
								done = false;
							}
							cnt++; 
						}
					}
					if (dbg) StdStreams.vrb.println();

					// resolve cycles
					done = false;
					while (!done) {
						cnt = paramStartGPR + 2; done = true;
						while (srcGPR[cnt] != 0) {
							int src = 0;
							if (srcGPRcount[cnt] == 1) {
								src = cnt;
								createIrArSrB(ppcOr, 0, srcGPR[cnt], srcGPR[cnt]);
								srcGPRcount[srcGPR[cnt]]--;
								done = false;
							}
							boolean done1 = false;
							while (!done1) {
								int k = paramStartGPR + 2; done1 = true;
								while (srcGPR[k] != 0) {
									if (srcGPRcount[k] == 0 && k != src) {
										createIrArSrB(ppcOr, k, srcGPR[k], srcGPR[k]);
										srcGPRcount[k]--; srcGPRcount[srcGPR[k]]--; 
										done1 = false;
									}
									k++; 
								}
							}
							if (src != 0) {
								createIrArSrB(ppcOr, src, 0, 0);
								srcGPRcount[src]--;
							}
							cnt++;
						}
					}
					loadConstantAndFixup(paramStartGPR, item);	// ref to type descriptor
					createIrDrAsimm(ppcAddi, paramStartGPR+1, 0, opds.length);	// nofDimensions
					createIBOBILK(ppcBclr, BOalways, 0, true);
					createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
				}
				break;
			case sCreturn:
				opds = instr.getOperands();
				bci = ssa.cfg.code[node.lastBCA] & 0xff;
				switch (bci) {
				case bCreturn:
					break;
				case bCireturn:
				case bCareturn:
					createIrArSrB(ppcOr, returnGPR1, opds[0].reg, opds[0].reg);
					break;
				case bClreturn:
					createIrArSrB(ppcOr, returnGPR1, opds[0].regLong, opds[0].regLong);
					createIrArSrB(ppcOr, returnGPR2, opds[0].reg, opds[0].reg);
					break;
				case bCfreturn:
				case bCdreturn:
					createIrDrB(ppcFmr, returnFPR, opds[0].reg);
					break;
				default:
					ErrorReporter.reporter.error(620);
					assert false : "return instruction not implemented";
					return;
				}
				if (node.next != null)	// last node needs no branch
					createIli(ppcB, 0, false);
				break;
			case sCthrow:
				opds = instr.getOperands();
				sReg1 = opds[0].reg;
				ref = (MonadicRef)instr;
				t = (Type)ref.item;
				if (t instanceof Class) offset = ((Class)t).extensionLevel;
				else offset = 1;	// object is an array
				createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);
				createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 6);	// jump to label 1 if null pointer
				createIrDrAd(ppcLwz, res.regAux1, sReg1, -4);
				createIrDrAd(ppcLwz, 0, res.regAux1, 8 + offset * 4);
				loadConstantAndFixup(res.regAux1, t);	// addr of type
				createItrap(ppcTw, TOifnequal, res.regAux1, 0);
				// label 1
//				createIrArSrB(ppcOr, res.reg, sReg1, sReg1);				
				break;
			case sCbranch:
				bci = ssa.cfg.code[node.lastBCA] & 0xff;
				switch (bci) {
				case bCgoto:
					createIli(ppcB, 0, false);
					break;
				case bCif_acmpeq:
				case bCif_acmpne:
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					sReg2 = opds[1].reg;
					createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);
					if (bci == bCif_acmpeq)
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
					else
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 0);
					break;
				case bCif_icmpeq:
				case bCif_icmpne:
				case bCif_icmplt:
				case bCif_icmpge:
				case bCif_icmpgt:
				case bCif_icmple:
					boolean inverted = false;
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					sReg2 = opds[1].reg;
					if (sReg1 < 0) {
						if (opds[0].constant != null) {
							int immVal = ((StdConstant)opds[0].constant).valueH;
							if ((immVal >= -32768) && (immVal <= 32767))
								createICRFrAsimm(ppcCmpi, CRF0, sReg2, immVal);
							else
								createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);
						} else
								createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);					
					} else if (sReg2 < 0) {
						if (opds[1].constant != null) {
							int immVal = ((StdConstant)opds[1].constant).valueH;
							if ((immVal >= -32768) && (immVal <= 32767)) {
								inverted = true;
								createICRFrAsimm(ppcCmpi, CRF0, sReg1, immVal);
							} else
								createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);
						} else
							createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);					
					} else {
						createICRFrArB(ppcCmp, CRF0, sReg2, sReg1);
					}
					if (!inverted) {
						if (bci == bCif_icmpeq) 
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
						else if (bci == bCif_icmpne)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 0);
						else if (bci == bCif_icmplt)
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 0);
						else if (bci == bCif_icmpge)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 0);
						else if (bci == bCif_icmpgt)
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, 0);
						else if (bci == bCif_icmple)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 0);
					} else {
						if (bci == bCif_icmpeq) 
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
						else if (bci == bCif_icmpne)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 0);
						else if (bci == bCif_icmplt)
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, 0);
						else if (bci == bCif_icmpge)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 0);
						else if (bci == bCif_icmpgt)
							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 0);
						else if (bci == bCif_icmple)
							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 0);
					}
					break;
				case bCifeq:
				case bCifne:
				case bCiflt:
				case bCifge:
				case bCifgt:
				case bCifle:
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);
					if (bci == bCifeq) 
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
					else if (bci == bCifne)
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 0);
					else if (bci == bCiflt)
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 0);
					else if (bci == bCifge)
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 0);
					else if (bci == bCifgt)
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, 0);
					else if (bci == bCifle)
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 0);
					break;
				case bCifnonnull:
				case bCifnull:
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);
					if (bci == bCifnonnull)
						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 0);
					else
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
					break;
				case bCtableswitch:
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					int addr = node.lastBCA + 1;
					addr = (addr + 3) & -4; // round to the next multiple of 4
					addr += 4; // skip default offset
					int low = getInt(ssa.cfg.code, addr);
					int high = getInt(ssa.cfg.code, addr + 4);
					int nofCases = high - low + 1;
					for (int k = 0; k < nofCases; k++) {
						createICRFrAsimm(ppcCmpi, CRF0, sReg1, low + k);
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
					}
					createIli(ppcB, nofCases, false);
					break;
				case bClookupswitch:
					opds = instr.getOperands();
					sReg1 = opds[0].reg;
					addr = node.lastBCA + 1;
					addr = (addr + 3) & -4; // round to the next multiple of 4
					addr += 4; // skip default offset
					int nofPairs = getInt(ssa.cfg.code, addr);
					for (int k = 0; k < nofPairs; k++) {
						int key = getInt(ssa.cfg.code, addr + 4 + k * 8);
						createICRFrAsimm(ppcCmpi, CRF0, sReg1, key);
						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
					}
					createIli(ppcB, nofPairs, true);
					break;
				default:
					ErrorReporter.reporter.error(621);
					assert false : "branch instruction not implemented";
					return;
				}
				break;
			case sCregMove:
				opds = instr.getOperands();
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					createIrArSrB(ppcOr, res.reg, opds[0].reg, opds[0].reg);
					break;
				case tLong:
					createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
					createIrArSrB(ppcOr, res.reg, opds[0].reg, opds[0].reg);
					break;
				case tFloat: case tDouble:
					createIrDrB(ppcFmr, res.reg, opds[0].reg);
					break;
				default:
					if (dbg) StdStreams.vrb.println("type = " + (res.type& 0x7fffffff));
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;
			default:
				ErrorReporter.reporter.error(625);
				assert false : "SSA instruction not implemented" + instr.scMnemonics[instr.ssaOpcode] + " function";
				return;
			}
		}
	}

	private void copyParameters(SSAValue[] opds) {
		int offset = 0;
		for (int k = 0; k < nofGPR; k++) {srcGPR[k] = 0; srcGPRcount[k] = 0;}
		for (int k = 0; k < nofFPR; k++) {srcFPR[k] = 0; srcFPRcount[k] = 0;}

		// get info about in which register parameters are located
		// parameters which go onto the stack are treated equally
		for (int k = 0, kGPR = 0, kFPR = 0; k < opds.length; k++) {
			int type = opds[k].type & ~(1<<ssaTaFitIntoInt);
			if (type == tLong) {
				srcGPR[kGPR + paramStartGPR] = opds[k].regLong;
				srcGPR[kGPR + 1 + paramStartGPR] = opds[k].reg;
				kGPR += 2;
			} else if (type == tFloat || type == tDouble) {
				srcFPR[kFPR + paramStartFPR] = opds[k].reg;
				kFPR++;
			} else {
				srcGPR[kGPR + paramStartGPR] = opds[k].reg;
				kGPR++;
			}
		}
		
		// count register usage
		int i = paramStartGPR;
		while (srcGPR[i] != 0) srcGPRcount[srcGPR[i++]]++;
		i = paramStartFPR;
		while (srcFPR[i] != 0) srcFPRcount[srcFPR[i++]]++;
//		if (dbg) {
//			StdStreams.vrb.print("srcGPR = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPR[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("srcGPRcount = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPRcount[i] + ","); 
//			StdStreams.vrb.println();
//		}
		
		// handle move to itself
		i = paramStartGPR;
		while (srcGPR[i] != 0) {
			if (srcGPR[i] == i) {
//				if (dbg) StdStreams.vrb.println("move to itself");
				if (i <= paramEndGPR) srcGPRcount[i]--;
				else srcGPRcount[i]--;	// copy to stack
			}
			i++;
		}
//		if (dbg) {
//			StdStreams.vrb.print("srcGPR = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPR[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("srcGPRcount = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPRcount[i] + ","); 
//			StdStreams.vrb.println();
//		}
		i = paramStartFPR;
		while (srcFPR[i] != 0) {
			if (srcFPR[i] == i) {
				if (i <= paramEndFPR) srcFPRcount[i]--;
				else srcFPRcount[i]--;	// copy to stack
			}
			i++;
		}

		// move registers 
		boolean done = false;
		while (!done) {
			i = paramStartGPR; done = true;
			while (srcGPR[i] != 0) {
				if (i > paramEndGPR) {	// copy to stack
					if (srcGPRcount[i] >= 0) { // check if not done yet
						if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (i-paramStartGPR) + " from register " + srcGPR[i] + " to stack slot");
						createIrSrAsimm(ppcStw, srcGPR[i], stackPtr, paramOffset + offset);
						offset += 4;
						srcGPRcount[i]=-1; srcGPRcount[srcGPR[i]]--; 
						done = false;
					}
				} else {
					if (srcGPRcount[i] == 0) { // check if register no longer used for parameter
						if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (i-paramStartGPR) + " from register " + srcGPR[i] + " to " + i);
						createIrArSrB(ppcOr, i, srcGPR[i], srcGPR[i]);
						srcGPRcount[i]--; srcGPRcount[srcGPR[i]]--; 
						done = false;
					}
				}
				i++; 
			}
		}
		if (dbg) StdStreams.vrb.println();
		done = false;
		while (!done) {
			i = paramStartFPR; done = true;
			while (srcFPR[i] != 0) {
				if (i > paramEndFPR) {	// copy to stack
					if (srcFPRcount[i] >= 0) { // check if not done yet
						createIrSrAd(ppcStfd, srcFPR[i], stackPtr, paramOffset + offset);
						offset += 8;
						srcFPRcount[i]=-1; srcFPRcount[srcFPR[i]]--; 
						done = false;
					}
				} else {
					if (srcFPRcount[i] == 0) { // check if register no longer used for parameter
						createIrDrB(ppcFmr, i, srcFPR[i]);
						srcFPRcount[i]--; srcFPRcount[srcFPR[i]]--; 
						done = false;
					}
				}
				i++; 
			}
		}

		// resolve cycles
		done = false;
		while (!done) {
			i = paramStartGPR; done = true;
			while (srcGPR[i] != 0) {
				int src = 0;
				if (srcGPRcount[i] == 1) {
					src = i;
					createIrArSrB(ppcOr, 0, srcGPR[i], srcGPR[i]);
					srcGPRcount[srcGPR[i]]--;
					done = false;
				}
				boolean done1 = false;
				while (!done1) {
					int k = paramStartGPR; done1 = true;
					while (srcGPR[k] != 0) {
						if (srcGPRcount[k] == 0 && k != src) {
							createIrArSrB(ppcOr, k, srcGPR[k], srcGPR[k]);
							srcGPRcount[k]--; srcGPRcount[srcGPR[k]]--; 
							done1 = false;
						}
						k++; 
					}
				}
				if (src != 0) {
					createIrArSrB(ppcOr, src, 0, 0);
					srcGPRcount[src]--;
				}
				i++;
			}
		}
		done = false;
		while (!done) {
			i = paramStartFPR; done = true;
			while (srcFPR[i] != 0) {
				int src = 0;
				if (srcFPRcount[i] == 1) {
					src = i;
					createIrDrB(ppcFmr, 0, srcFPR[i]);
					srcFPRcount[srcFPR[i]]--;
					done = false;
				}
				boolean done1 = false;
				while (!done1) {
					int k = paramStartFPR; done1 = true;
					while (srcFPR[k] != 0) {
						if (srcFPRcount[k] == 0 && k != src) {
							createIrDrB(ppcFmr, k, srcFPR[k]);
							srcFPRcount[k]--; srcFPRcount[srcFPR[k]]--; 
							done1 = false;
						}
						k++; 
					}
				}
				if (src != 0) {
					createIrDrB(ppcFmr, src, 0);
					srcFPRcount[src]--;
				}
				i++;
			}
		}
	}

	private static int getInt(byte[] bytes, int index){
		return (((bytes[index]<<8) | (bytes[index+1]&0xFF))<<8 | (bytes[index+2]&0xFF))<<8 | (bytes[index+3]&0xFF);
	}

	private void createIrArS(int opCode, int rA, int rS) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16);
		incInstructionNum();
	}

	private void createIrD(int opCode, int rD) {
		instructions[iCount] = opCode | (rD << 21);
		incInstructionNum();
	}

	private void createIrS(int opCode, int rD) {
		instructions[iCount] = opCode | (rD << 21);
		incInstructionNum();
	}

	private void createIrDrArB(int opCode, int rD, int rA, int rB) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16) | (rB << 11);
		incInstructionNum();
	}

	private void createIrDrArC(int opCode, int rD, int rA, int rC) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16) | (rC << 6);
		incInstructionNum();
	}

	private void createIrDrArCrB(int opCode, int rD, int rA, int rC, int rB) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16) | (rC << 6) | (rB << 11);
		incInstructionNum();
	}
	
	private void createIrSrArB(int opCode, int rS, int rA, int rB) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (rB << 11);
		incInstructionNum();
	}

	private void createIrArSrB(int opCode, int rA, int rS, int rB) {
		if ((opCode == ppcOr) && (rA == rS) && (rA == rB)) return; 	// lr x,x makes no sense
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (rB << 11);
		incInstructionNum();
	}

	private void createIrDrAd(int opCode, int rD, int rA, int d) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16) | (d  & 0xffff);
		incInstructionNum();
	}

	private void createIrDrAsimm(int opCode, int rD, int rA, int simm) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16) | (simm  & 0xffff);
		incInstructionNum();
	}

	private void createIrArSuimm(int opCode, int rA, int rS, int uimm) {
		instructions[iCount] = opCode | (rA << 16) | (rS << 21) | (uimm  & 0xffff);
		incInstructionNum();
	}

	private void createIrSrAsimm(int opCode, int rS, int rA, int simm) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (simm  & 0xffff);
		incInstructionNum();
	}

	private void createIrSrAd(int opCode, int rS, int rA, int d) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (d  & 0xffff);
		incInstructionNum();
	}

	private void createIrArSSH(int opCode, int rA, int rS, int SH) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (SH << 11);
		incInstructionNum();
	}

	private void createIrArSSHMBME(int opCode, int rA, int rS, int SH, int MB, int ME) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (SH << 11) | (MB << 6) | (ME << 1);
		incInstructionNum();
	}

	private void createIrArSrBMBME(int opCode, int rA, int rS, int rB, int MB, int ME) {
		instructions[iCount] = opCode | (rS << 21) | (rA << 16) | (rB << 11) | (MB << 6) | (ME << 1);
		incInstructionNum();
	}

	private void createItrap(int opCode, int TO, int rA, int rB) {
		instructions[iCount] = opCode | (TO << 21) | (rA << 16) | (rB << 11);
		incInstructionNum();
	}

	private void createItrapSimm(int opCode, int TO, int rA, int imm) {
		instructions[iCount] = opCode | (TO << 21) | (rA << 16) | (imm & 0xffff);
		incInstructionNum();
	}

	private void createIli(int opCode, int LI, boolean link) {
		instructions[iCount] = opCode | (LI << 2 | (link ? 1 : 0));
		incInstructionNum();
	}

	private void createIBOBIBD(int opCode, int BO, int BI, int BD) {
		instructions[iCount] = opCode | (BO << 21) | (BI << 16) | (BD << 2);
		incInstructionNum();
	}

	private void createIBOBILK(int opCode, int BO, int BI, boolean link) {
		instructions[iCount] = opCode | (BO << 21) | (BI << 16) | (link?1:0);
		incInstructionNum();
	}

	private void createICRFrArB(int opCode, int crfD, int rA, int rB) {
		instructions[iCount] = opCode | (crfD << 23) | (rA << 16) | (rB << 11);
		incInstructionNum();
	}

	private void createICRFrAsimm(int opCode, int crfD, int rA, int simm) {
		instructions[iCount] = opCode | (crfD << 23) | (rA << 16) | (simm & 0xffff);
		incInstructionNum();
	}

	private void createIcrbDcrbAcrbB(int opCode, int crbD, int crbA, int crbB) {
		instructions[iCount] = opCode | (crbD << 21) | (crbA << 16) | (crbB << 11);
		incInstructionNum();
	}
	
	private void createICRMrS(int opCode, int CRM, int rS) {
		instructions[iCount] = opCode | (rS << 21) | (CRM << 12);
		incInstructionNum();
	}
	
	private void createIFMrB(int opCode, int FM, int rB) {
		instructions[iCount] = opCode | (FM << 17) | (rB << 11);
		incInstructionNum();
	}
	
	private void createIrDrA(int opCode, int rD, int rA) {
		instructions[iCount] = opCode | (rD << 21) | (rA << 16);
		incInstructionNum();
	}

	private void createIrDrB(int opCode, int rD, int rB) {
		if ((opCode == ppcFmr) && (rD == rB)) return; 	// fmr x,x makes no sense
		instructions[iCount] = opCode | (rD << 21) | (rB << 11);
		incInstructionNum();
	}

	private void createIrSspr(int opCode, int spr, int rS) {
		int temp = ((spr & 0x1F) << 5) | ((spr & 0x3E0) >> 5);
		if (spr == 268 || spr == 269) opCode = ppcMftb;
		instructions[iCount] = opCode | (temp << 11) | (rS << 21);
		incInstructionNum();
	}

	private void createIrfi(int opCode) {
		instructions[iCount] = opCode;
		incInstructionNum();
	}

	private void loadConstant(int val, int reg) {
		int low = val & 0xffff;
		int high = (val >> 16) & 0xffff;
		if ((low >> 15) == 0) {
			if (low != 0 && high != 0) {
				createIrDrAsimm(ppcAddi, reg, 0, low);
				createIrDrAsimm(ppcAddis, reg, reg, high);
			} else if (low == 0 && high != 0) {
				createIrDrAsimm(ppcAddis, reg, 0, high);		
			} else if (low != 0 && high == 0) {
				createIrDrAsimm(ppcAddi, reg, 0, low);
			} else createIrDrAsimm(ppcAddi, reg, 0, 0);
		} else {
			createIrDrAsimm(ppcAddi, reg, 0, low);
			if (((high + 1) & 0xffff) != 0) createIrDrAsimm(ppcAddis, reg, reg, high + 1);
		}
	}
	
	private void loadConstantAndFixup(int reg, Item item) {
		if (lastFixup < 0 || lastFixup > 32768) {ErrorReporter.reporter.error(602); return;}
		createIrDrAsimm(ppcAddi, reg, 0, lastFixup);
		createIrDrAsimm(ppcAddis, reg, reg, 0);
		lastFixup = iCount - 2;
		fixups[fCount] = item;
		fCount++;
		int len = fixups.length;
		if (fCount == len) {
			Item[] newFixups = new Item[2 * len];
			for (int k = 0; k < len; k++)
				newFixups[k] = fixups[k];
			fixups = newFixups;
		}		
	}
	
	public void doFixups() {
		int currInstr = lastFixup;
		int currFixup = fCount - 1;
		while (currFixup >= 0) {
			Item item = fixups[currFixup];
			int addr;
			if (item == null) // item is null if constant null is loaded (aconst_null) 
				addr = 0;
			else 
				addr = fixups[currFixup].address;
//			if (dbg) { 
//				StdStreams.vrb.print("\t fix item ");
//				if(item == null) StdStreams.vrb.print("null"); 
//				else item.printName();
//				StdStreams.vrb.println(" at address = " + Integer.toHexString(addr));
//			}
			int low = addr & 0xffff;
			int high = (addr >> 16) & 0xffff;
			if (!((low >> 15) == 0)) high++;
			int nextInstr = instructions[currInstr] & 0xffff;
			instructions[currInstr] = (instructions[currInstr] & 0xffff0000) | (low & 0xffff);
			instructions[currInstr+1] = (instructions[currInstr+1] & 0xffff0000) | (high & 0xffff);
			currInstr = nextInstr;
			currFixup--;
		}
	}

	private void incInstructionNum() {
		iCount++;
		int len = instructions.length;
		if (iCount == len) {
			int[] newInstructions = new int[2 * len];
			for (int k = 0; k < len; k++)
				newInstructions[k] = instructions[k];
			instructions = newInstructions;
		}
	}

	private void insertProlog() {
		iCount = 0;
		createIrSrAsimm(ppcStwu, stackPtr, stackPtr, -stackSize);
		createIrSspr(ppcMfspr, LR, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, LRoffset);
		if (nofNonVolGPR > 0) {
			createIrSrAd(ppcStmw, nofGPR-nofNonVolGPR, stackPtr, GPRoffset);
		}
		if (enFloatsInExc) {
			createIrD(ppcMfmsr, 0);
			createIrArSuimm(ppcOri, 0, 0, 0x2000);
			createIrS(ppcMtmsr, 0);
		}
		int offset = 0;
		if (nofNonVolFPR > 0) {
			for (int i = 0; i < nofNonVolFPR; i++) {
				createIrSrAd(ppcStfd, topFPR-i, stackPtr, FPRoffset + offset);
				offset += 8;
			}
		}
		if (enFloatsInExc) {
			for (int i = 0; i < nonVolStartFPR; i++) {
				createIrSrAd(ppcStfd, i, stackPtr, FPRoffset + offset);
				offset += 8;
			}
			createIrD(ppcMffs, 0);
			createIrSrAd(ppcStfd, 0, stackPtr, FPRoffset + offset);
		}
//		if (dbg) {
//			StdStreams.vrb.print("moveGPRsrc = ");
//			for (int i = 0; moveGPRsrc[i] != 0; i++) StdStreams.vrb.print(moveGPRsrc[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveGPRdst = ");
//			for (int i = 0; moveGPRdst[i] != 0; i++) StdStreams.vrb.print(moveGPRdst[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveFPRsrc = ");
//			for (int i = 0; moveFPRsrc[i] != 0; i++) StdStreams.vrb.print(moveFPRsrc[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveFPRdst = ");
//			for (int i = 0; moveFPRdst[i] != 0; i++) StdStreams.vrb.print(moveFPRdst[i] + ","); 
//			StdStreams.vrb.println();
//		}
		for (int i = 0; i < nofMoveGPR; i++) {
			if (moveGPRsrc[i]+paramStartGPR <= paramEndGPR) // copy from parameter register
				createIrArSrB(ppcOr, moveGPRdst[i], moveGPRsrc[i]+paramStartGPR, moveGPRsrc[i]+paramStartGPR);
			else { // copy from stack slot
				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + (i+(paramEndGPR-paramStartGPR+1)) + " from stack slot into GPR " + (paramRegNr[paramEndGPR - paramStartGPR + 1 + i]));
				createIrDrAd(ppcLwz, moveGPRdst[i], stackPtr, stackSize + paramOffset + (i)*4);
			}
		}
		for (int i = 0; i < nofMoveFPR; i++) {
			if (moveFPRsrc[i]+paramStartFPR <= paramEndFPR) // copy from parameter register
				createIrDrB(ppcFmr, moveFPRdst[i], moveFPRsrc[i]+paramStartFPR);
			else { // copy from stack slot
				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + (i+(paramEndFPR-paramStartFPR+1)) + " from stack slot into FPR " + (paramRegNr[paramEndFPR - paramStartFPR + 1 + i]));
				createIrDrAd(ppcLfd, moveFPRdst[i], stackPtr, stackSize + paramOffset + (i)*8);
			}
		}
	}

	private void insertPrologException() {
		iCount = 0;
		createIrSrAsimm(ppcStwu, stackPtr, stackPtr, -stackSize);
		createIrSrAsimm(ppcStw, 0, stackPtr, GPRoffset);
		createIrSspr(ppcMfspr, SRR0, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, SRR0offset);
		createIrSspr(ppcMfspr, SRR1, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, SRR1offset);
		createIrSspr(ppcMtspr, EID, 0);
		createIrSspr(ppcMfspr, LR, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, LRoffset);
		createIrSspr(ppcMfspr, CTR, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, CTRoffset);
		createIrD(ppcMfcr, 0);
		createIrSrAsimm(ppcStw, 0, stackPtr, CRoffset);
		createIrSrAd(ppcStmw, 2, stackPtr, GPRoffset + 8);
		if (enFloatsInExc) {
			createIrD(ppcMfmsr, 0);
			createIrArSuimm(ppcOri, 0, 0, 0x2000);
			createIrS(ppcMtmsr, 0);
			int offset = 0;
			if (nofNonVolFPR > 0) {
				for (int i = 0; i < nofNonVolFPR; i++) {
					createIrSrAd(ppcStfd, topFPR-i, stackPtr, FPRoffset + offset);
					offset += 8;
				}
			}
			for (int i = 0; i < nonVolStartFPR; i++) {
				createIrSrAd(ppcStfd, i, stackPtr, FPRoffset + offset);
				offset += 8;
			}
			createIrD(ppcMffs, 0);
			createIrSrAd(ppcStfd, 0, stackPtr, FPRoffset + offset);
		}
	}

	private void insertEpilog(int stackSize) {
		int offset = (nonVolStartFPR + nofNonVolFPR + 1) * 8;
		if (enFloatsInExc) {
			createIrDrAd(ppcLfd, 0, stackPtr, FPRoffset + offset);
			createIFMrB(ppcMtfsf, 0xff, 0);
			offset -= 8;
			for (int i = 0; i < nonVolStartFPR; i++) {
				createIrDrAd(ppcLfd, i, stackPtr, FPRoffset + offset);
				offset -= 8;
			}
		}
		if (nofNonVolFPR > 0) {
			for (int i = 0; i < nofNonVolFPR; i++)
				createIrDrAd(ppcLfd, topFPR-i, stackPtr, FPRoffset + i * 8);
		}
		if (nofNonVolGPR > 0)
			createIrDrAd(ppcLmw, nofGPR - nofNonVolGPR, stackPtr, GPRoffset);
		createIrDrAd(ppcLwz, 0, stackPtr, LRoffset);
		createIrSspr(ppcMtspr, LR, 0);
		createIrDrAsimm(ppcAddi, stackPtr, stackPtr, stackSize);
		createIBOBILK(ppcBclr, BOalways, 0, false);
	}

	private void insertEpilogException(int stackSize) {
		int offset = (nonVolStartFPR + nofNonVolFPR + 1) * 8;
		if (enFloatsInExc) {
			createIrDrAd(ppcLfd, 0, stackPtr, FPRoffset + offset);
			createIFMrB(ppcMtfsf, 0xff, 0);
			offset -= 8;
			for (int i = 0; i < nonVolStartFPR; i++) {
				createIrDrAd(ppcLfd, i, stackPtr, FPRoffset + offset);
				offset -= 8;
			}
		}
		if (nofNonVolFPR > 0) {
			for (int i = 0; i < nofNonVolFPR; i++)
				createIrDrAd(ppcLfd, topFPR-i, stackPtr, FPRoffset + i * 8);
		}
		createIrDrAd(ppcLmw, 2, stackPtr, GPRoffset + 8);
		createIrDrAd(ppcLwz, 0, stackPtr, CRoffset);
		createICRMrS(ppcMtcrf, 0xff, 0);
		createIrDrAd(ppcLwz, 0, stackPtr, CTRoffset);
		createIrSspr(ppcMtspr, CTR, 0);
		createIrDrAd(ppcLwz, 0, stackPtr, LRoffset);
		createIrSspr(ppcMtspr, LR, 0);
		createIrDrAd(ppcLwz, 0, stackPtr, SRR1offset);
		createIrSspr(ppcMtspr, SRR1, 0);
		createIrDrAd(ppcLwz, 0, stackPtr, SRR0offset);
		createIrSspr(ppcMtspr, SRR0, 0);
		createIrDrAd(ppcLwz, 0, stackPtr, GPRoffset);
		createIrDrAsimm(ppcAddi, stackPtr, stackPtr, stackSize);
		createIrfi(ppcRfi);
	}
	
//	public void print(){
//		StdStreams.vrb.println("Code for Method: " + ssa.cfg.method.owner.name + "." + ssa.cfg.method.name +  ssa.cfg.method.methDescriptor);
//		for (int i = 0; i < iCount; i++){
//			StdStreams.vrb.print("\t" + Integer.toHexString(instructions[i]));
//			StdStreams.vrb.print("\t[0x");
//			StdStreams.vrb.print(Integer.toHexString(i*4));
//			StdStreams.vrb.print("]\t" + InstructionDecoder.getMnemonic(instructions[i]));
//			int opcode = (instructions[i] & 0xFC000000) >>> (31 - 5);
//		if (opcode == 0x10) {
//			int BD = (short)(instructions[i] & 0xFFFC);
//			StdStreams.vrb.print(", [0x" + Integer.toHexString(BD + 4 * i) + "]\t");
//		} else if (opcode == 0x12) {
//			int li = (instructions[i] & 0x3FFFFFC) << 6 >> 6;
//			StdStreams.vrb.print(", [0x" + Integer.toHexString(li + 4 * i) + "]\t");
//		}
//		StdStreams.vrb.println();
//		}
//	}

	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append("Code for Method: " + ssa.cfg.method.owner.name + "." + ssa.cfg.method.name +  ssa.cfg.method.methDescriptor + "\n");
		for (int i = 0; i < iCount; i++){
//			sb.append("\t" + String.format("%08X",instructions[i]));
			sb.append("\t" + String.format("%08X",instructions[i]));
			sb.append("\t[0x");
			sb.append(Integer.toHexString(i*4));
			sb.append("]\t" + InstructionDecoder.getMnemonic(instructions[i]));
			int opcode = (instructions[i] & 0xFC000000) >>> (31 - 5);
				if (opcode == 0x10) {
					int BD = (short)(instructions[i] & 0xFFFC);
					sb.append(", [0x" + Integer.toHexString(BD + 4 * i) + "]\t");
				} else if (opcode == 0x12) {
					int li = (instructions[i] & 0x3FFFFFC) << 6 >> 6;
					sb.append(", [0x" + Integer.toHexString(li + 4 * i) + "]\t");
				}
				sb.append("\n");
		}
		return sb.toString();
	}

	public static void init() { 
		idPUT1 = 0x001;	// same as in rsc/ntbMpc555STS.deep
		idPUT2 = 0x002;
		idPUT4 = 0x003;
		idPUT8 = 0x004;
		idGET1 = 0x005;	
		idGET2 = 0x006;
		idGET4 = 0x007;
		idGET8 = 0x008;
		idBIT = 0x009;
		idASM = 0x00a;
		idGETGPR = 0x00b;
		idGETFPR = 0x00c;
		idGETSPR = 0x00d;
		idPUTGPR = 0x00e;
		idPUTFPR = 0x00f;
		idPUTSPR = 0x010;
		idADR_OF_METHOD = 0x011;
		idHALT = 0x012;
		idENABLE_FLOATS = 0x013;
		idDoubleToBits = 0x106;
		idBitsToDouble = 0x107;
		objectSize = Type.wktObject.getObjectSize();
		stringSize = Type.wktString.getObjectSize();
		int2floatConst1 = Linker32.addGlobalConstant((double)(0x10000000000000L + 0x80000000L));
		int2floatConst2 = Linker32.addGlobalConstant((double)0x100000000L);
		int2floatConst3 = Linker32.addGlobalConstant((double)0x10000000000000L);
		final Class stringClass = (Class)Type.wktString;
		final Class heapClass = (Class)Type.classList.getItemByName(Configuration.getHeapClassname().toString());
		if (stringClass != null) {
			stringNewstringMethod = (Method)stringClass.methods.getItemByName("newstring"); // TODO improve this
			if(heapClass != null) {
				heapNewstringMethod = (Method)heapClass.methods.getItemByName("newstring"); // TODO improve this
			}
			if(dbg) {
				if (stringNewstringMethod != null) StdStreams.vrb.println("stringNewstringMethod = " + stringNewstringMethod.name + stringNewstringMethod.methDescriptor); else StdStreams.vrb.println("stringNewstringMethod: not found");
				if (heapNewstringMethod != null) StdStreams.vrb.println("heapNewstringMethod = " + heapNewstringMethod.name + heapNewstringMethod.methDescriptor); else StdStreams.vrb.println("heapNewstringMethod: not found");
			}
			
			Method m = (Method)stringClass.methods;		
			while (m != null) {
				if (m.name.equals(HString.getHString("<init>"))) {
					if (m.methDescriptor.equals(HString.getHString("([C)V"))) strInitC = m; 
					else if (m.methDescriptor.equals(HString.getHString("([CII)V"))) strInitCII = m;
				}
				m = (Method)m.next;
			}		
			if(dbg) {
				if (strInitC != null) StdStreams.vrb.println("stringInitC = " + strInitC.name + strInitC.methDescriptor); else StdStreams.vrb.println("stringInitC: not found");
				if (strInitCII != null) StdStreams.vrb.println("stringInitCII = " + strInitCII.name + strInitCII.methDescriptor); else StdStreams.vrb.println("stringInitCII: not found");
			}
			
			m = (Method)stringClass.methods;		
			while (m != null) {
				if (m.name.equals(HString.getHString("allocateString"))) {
					if (m.methDescriptor.equals(HString.getHString("(I[C)Ljava/lang/String;"))) strAllocC = m; 
					else if (m.methDescriptor.equals(HString.getHString("(I[CII)Ljava/lang/String;"))) strAllocCII = m;
				}
				m = (Method)m.next;
			}		
			if(dbg) {
				if (strAllocC != null) StdStreams.vrb.println("allocateStringC = " + strAllocC.name + strAllocC.methDescriptor); else StdStreams.vrb.println("allocateStringC: not found");
				if (strAllocCII != null) StdStreams.vrb.println("allocateStringCII = " + strAllocCII.name + strAllocCII.methDescriptor); else StdStreams.vrb.println("allocateStringCII: not found");
			}
		}
	}

}
