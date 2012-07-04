package ch.ntb.inf.deep.config;

import ch.ntb.inf.deep.strings.HString;

public class Programmer extends ConfigElement {

	private HString description;
	
	public Programmer(String jname) {
		this.name = HString.getRegisteredHString(jname);
	}
	
	public void setDescription(String desc) {
		this.description = HString.getRegisteredHString(desc);
	}
	
	public HString getDescription() {
		return this.description;
	}
	
}
