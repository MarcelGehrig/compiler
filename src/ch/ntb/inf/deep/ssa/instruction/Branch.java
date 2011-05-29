package ch.ntb.inf.deep.ssa.instruction;

import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.ssa.SSAValue;

public class Branch extends SSAInstruction {
	
	public Branch(int opCode, SSAValue operand1, SSAValue operand2){
		ssaOpcode = opCode;
		operands = new SSAValue[]{operand1,operand2};
	}

	public Branch(int opCode, SSAValue operand1){
		ssaOpcode = opCode;
		operands = new SSAValue[]{operand1};
	}

	public Branch(int opCode){
		ssaOpcode = opCode;
	}

	@Override
	public SSAValue[] getOperands() {
		return operands;
	}

	@Override
	public void setOperands(SSAValue[] operands) {
		if (operands.length > 0) {
			this.operands = operands;
		} else {
			throw new IndexOutOfBoundsException();
		}
	}

	@Override
	public void print(int level) {
		for (int i = 0; i < level*3; i++) StdStreams.out.print(" ");
		StdStreams.vrb.print(result.n + ": ");
		if (operands == null)
			StdStreams.vrb.print("Branch["+ scMnemonics[ssaOpcode]+"] ");
		else {
			if (operands.length == 2)
				StdStreams.vrb.print("Branch["+ scMnemonics[ssaOpcode]+"] {"+ operands[0].n + ", " + operands[1].n + "}");
			else
				StdStreams.vrb.print("Branch["+ scMnemonics[ssaOpcode]+"] {"+ operands[0].n + "}");
		}
		StdStreams.vrb.print(" (" + result.typeName() + ")");
		StdStreams.vrb.println();

	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append(result.n + ": ");
		if (operands == null)
			sb.append("Branch["+ scMnemonics[ssaOpcode]+"] ");
		else {
			if (operands.length == 2)
				sb.append("Branch["+ scMnemonics[ssaOpcode]+"] {"+ operands[0].n + ", " + operands[1].n + "}");
			else
				sb.append("Branch["+ scMnemonics[ssaOpcode]+"] {"+ operands[0].n + "}");
		}
		sb.append(" (" + result.typeName() + ")");
		
		return sb.toString();
	}
	
}
