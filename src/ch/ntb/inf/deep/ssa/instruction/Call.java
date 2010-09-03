package ch.ntb.inf.deep.ssa.instruction;

import ch.ntb.inf.deep.ssa.SSAValue;
import ch.ntb.inf.deep.strings.HString;

public class Call extends SSAInstruction {
	
	HString methodName;
	HString className;

	public Call(int opCode) {
		ssaOpcode = opCode;
	}
	
	public Call(int opCode, HString className , HString methodName){
		ssaOpcode = opCode;
		this.methodName = methodName;
		this.className = className;
	}
	
	
	public Call(int opCode,SSAValue[] operands){
		ssaOpcode = opCode;
		this.operands = operands;
	}
	
	public Call(int opCode, HString className , HString methodName, SSAValue[] operands){
		ssaOpcode = opCode;
		this.methodName = methodName;
		this.className = className;
		this.operands = operands;
	}

	@Override
	public SSAValue[] getOperands() {
		return operands;
	}

	@Override
	public void setOperands(SSAValue[] operands) {
		this.operands = operands;
	}
	
	public void setArgs(HString className , HString methodName){
		this.methodName = methodName;
		this.className = className;
	}
	
	/**
	 * returns the ClassName at HString[0] and MethodeNamen at HString[1];
	 * @return HString[]
	 */
	public HString[] getArgs(){
		return new HString[]{this.className, this.methodName};
	}
	
	@Override
	public void print(int level) {
		for (int i = 0; i < level*3; i++)System.out.print(" ");
		System.out.print("Call["+ scMnemonics[ssaOpcode]+"] (");
		for (int i=0;i<operands.length-1;i++){
			System.out.print(operands[i].typeName()+", ");
		}
		if(operands.length > 0){
			System.out.println(operands[operands.length-1].typeName()+ ")");
		}else{
			System.out.println(")");
		}
	}

}
