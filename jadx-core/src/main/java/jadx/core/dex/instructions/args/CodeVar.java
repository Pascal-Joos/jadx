package jadx.core.dex.instructions.args;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import jadx.api.metadata.annotations.VarNode;

public class CodeVar {
	@Nullable
	private String name;
	@Nullable
	private ArgType type; // before type inference can be null and set only for immutable types
	private List<SSAVar> ssaVars = Collections.emptyList();

	private boolean isFinal;
	private boolean isThis;
	private boolean isDeclared;

	@Nullable
	private VarNode cachedVarNode; // set and used at codegen stage

	public static CodeVar fromMthArg(RegisterArg mthArg, boolean linkRegister) {
		CodeVar var = new CodeVar();
		var.setType(mthArg.getInitType());
		var.setName(mthArg.getName());
		var.setThis(mthArg.isThis());
		var.setDeclared(true);
		var.setThis(mthArg.isThis());
		if (linkRegister) {
			var.setSsaVars(Collections.singletonList(new SSAVar(mthArg.getRegNum(), 0, mthArg)));
		}
		return var;
	}

	@Nullable
	public String getName() {
		return name;
	}

	public void setName(@Nullable String name) {
		this.name = name;
	}

	public ArgType getType() {
		if (type == null) {
			throw new IllegalStateException("type is not initialized");
		}
		return type;
	}

	public void setType(@Nullable ArgType type) {
		this.type = type;
	}

	public List<SSAVar> getSsaVars() {
		return ssaVars;
	}

	public void addSsaVar(SSAVar ssaVar) {
		if (ssaVars.isEmpty()) {
			ssaVars = new ArrayList<>(3);
		}
		if (!ssaVars.contains(ssaVar)) {
			ssaVars.add(ssaVar);
		}
	}

	public void setSsaVars(List<SSAVar> ssaVars) {
		this.ssaVars = ssaVars;
	}

	public SSAVar getAnySsaVar() {
		if (ssaVars.isEmpty()) {
			throw new IllegalStateException("CodeVar without SSA variables attached: " + this);
		}
		return ssaVars.get(0);
	}

	public boolean isFinal() {
		return isFinal;
	}

	public void setFinal(boolean aFinal) {
		isFinal = aFinal;
	}

	public boolean isThis() {
		return isThis;
	}

	public void setThis(boolean aThis) {
		isThis = aThis;
	}

	public boolean isDeclared() {
		return isDeclared;
	}

	public void setDeclared(boolean declared) {
		isDeclared = declared;
	}

	@Nullable
	public VarNode getCachedVarNode() {
		return cachedVarNode;
	}

	public void setCachedVarNode(VarNode varNode) {
		this.cachedVarNode = varNode;
	}

	/**
	 * Merge flags with OR operator
	 */
	public void mergeFlagsFrom(CodeVar other) {
		if (other.isDeclared()) {
			setDeclared(true);
		}
		if (other.isThis()) {
			setThis(true);
		}
		if (other.isFinal()) {
			setFinal(true);
		}
	}

	@Override
	public String toString() {
		return (isFinal ? "final " : "") + type + ' ' + name;
	}
}
