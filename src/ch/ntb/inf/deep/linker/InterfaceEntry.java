/*
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

package ch.ntb.inf.deep.linker;

import ch.ntb.inf.deep.strings.HString;

public class InterfaceEntry extends ConstBlkEntry {
	
	private static final int size = 4;
	
	short ifaceID;
	short bmo;
		
	public InterfaceEntry(HString ifaceName, short ifaceID, short bmo) {
		this.name = ifaceName;
		this.ifaceID = ifaceID;
		this.bmo = bmo;
	}
	
	public int getOffset() {
		return this.bmo;
	}
	
	public int getID() {
		return this.ifaceID;
	}
	
	protected int getItemSize() {
		return size;
	}
	
	public void setBmo(int offset) {
		bmo = (short)offset;
	}

	protected int insertIntoArray(int[] a, int offset) {
		int index = offset / 4;
		int written = 0;
		if(offset + size <= a.length * 4) {
			a[index] = (int)this.ifaceID << 16 | ((int)this.bmo & 0xFFFF);
			written = size;
		}
		return written;
	}
	
	public byte[] getBytes() {
		byte[] bytes = new byte[size];
		int value = (int)this.ifaceID << 16 | ((int)this.bmo & 0xFFFF);
		for (int i = 0; i < size; ++i) {
		    int shift = i << 3; // i * 8
		    bytes[(size - 1) - i] = (byte)((value & (0xff << shift)) >>> shift);
		}
		return bytes;
	}
	
	public String toString() {
		return String.format("[%08X]", (int)this.ifaceID << 16 | ((int)this.bmo & 0xFFFF)) + " (" + name + ")";
	}

}