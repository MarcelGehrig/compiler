/*
 * Copyright 2011 - 2013 NTB University of Applied Sciences in Technology
 * Buchs, Switzerland, http://www.ntb.ch/inf
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 *   
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package ch.ntb.inf.deep.classItems;

import ch.ntb.inf.deep.host.Dbg;
import ch.ntb.inf.deep.strings.HString;

public class Constant extends Item {

	Constant(HString name, Type type){
		super(name, type);
		accAndPropFlags |= 1<<dpfConst;
	}

	//--- debug primitives
	public void printShort(int indentLevel){
		indent(indentLevel);
		vrb.print("field ");
		super.printShort(0);
	}

	public void printTypeCategory(){
		if(type != null) type.printTypeCategory(); else  vrb.print("(-)");
	}
	
	public void print(int indentLevel){
		indent(indentLevel);
		Dbg.printJavaAccAndPropertyFlags(this.accAndPropFlags);
		type.printTypeCategory(); type.printName();
		vrb.print(' '); printName();
		vrb.print(" //dFlags:");  Dbg.printDeepAccAndPropertyFlags(this.accAndPropFlags);
	}
}
