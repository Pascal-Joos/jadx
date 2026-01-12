package jadx.core.xmlgen.entry;

import java.util.List;

import javax.annotation.Nullable;

public final class ResourceEntry {

	private final int id;
	private final String pkgName;
	private final String typeName;
	private final String keyName;
	private final String config;

	private int parentRef;
	@Nullable
	private ProtoValue protoValue;
	@Nullable
	private RawValue simpleValue;
	@Nullable
	private List<RawNamedValue> namedValues;

	public ResourceEntry(int id, String pkgName, String typeName, String keyName, String config) {
		this.id = id;
		this.pkgName = pkgName;
		this.typeName = typeName;
		this.keyName = keyName;
		this.config = config;
	}

	public ResourceEntry copy(String newKeyName) {
		ResourceEntry copy = new ResourceEntry(id, pkgName, typeName, newKeyName, config);
		copy.parentRef = this.parentRef;
		copy.protoValue = this.protoValue;
		copy.simpleValue = this.simpleValue;
		copy.namedValues = this.namedValues;
		return copy;
	}

	public ResourceEntry copyWithId() {
		return copy(String.format("%s_res_0x%08x", keyName, id));
	}

	public int getId() {
		return id;
	}

	public String getPkgName() {
		return pkgName;
	}

	public String getTypeName() {
		return typeName;
	}

	public String getKeyName() {
		return keyName;
	}

	public String getConfig() {
		return config;
	}

	public void setParentRef(int parentRef) {
		this.parentRef = parentRef;
	}

	public int getParentRef() {
		return parentRef;
	}

	@Nullable
	public ProtoValue getProtoValue() {
		return protoValue;
	}

	public void setProtoValue(ProtoValue protoValue) {
		this.protoValue = protoValue;
	}

	@Nullable
	public RawValue getSimpleValue() {
		return simpleValue;
	}

	public void setSimpleValue(RawValue simpleValue) {
		this.simpleValue = simpleValue;
	}

	public void setNamedValues(List<RawNamedValue> namedValues) {
		this.namedValues = namedValues;
	}

	@Nullable
	public List<RawNamedValue> getNamedValues() {
		return namedValues;
	}

	@Override
	public String toString() {
		return "  0x" + Integer.toHexString(id) + " (" + id + ')' + config + " = " + typeName + '.' + keyName;
	}
}
