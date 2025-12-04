package jadx.core.dex.visitors.typeinference;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import jadx.core.dex.instructions.args.ArgType;

public class TypeInfo {

	private ArgType type = ArgType.UNKNOWN;

	private final Set<ITypeBound> bounds = new LinkedHashSet<>();

	@NotNull
	public ArgType getType() {
		return type;
	}

	public void setType(@Nullable ArgType type) {
		this.type = type != null ? type : ArgType.UNKNOWN;
	}

	public Set<ITypeBound> getBounds() {
		return bounds;
	}

	@Override
	public String toString() {
		return "TypeInfo{type=" + type + ", bounds=" + bounds + '}';
	}
}
