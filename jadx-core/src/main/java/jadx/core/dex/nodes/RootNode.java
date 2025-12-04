     1	package jadx.core.dex.nodes;
     2	
     3	import java.util.ArrayList;
     4	import java.util.Comparator;
     5	import java.util.HashMap;
     6	import java.util.List;
     7	import java.util.Map;
     8	import java.util.stream.Collectors;
     9	
    10	import org.jetbrains.annotations.NotNull;
    11	import org.jetbrains.annotations.Nullable;
    12	import org.slf4j.Logger;
    13	import org.slf4j.LoggerFactory;
    14	
    15	import jadx.api.ICodeCache;
    16	import jadx.api.ICodeWriter;
    17	import jadx.api.JadxArgs;
    18	import jadx.api.ResourceFile;
    19	import jadx.api.ResourceType;
    20	import jadx.api.ResourcesLoader;
    21	import jadx.api.data.ICodeData;
    22	import jadx.api.plugins.input.data.IClassData;
    23	import jadx.api.plugins.input.data.ILoadResult;
    24	import jadx.core.Jadx;
    25	import jadx.core.ProcessClass;
    26	import jadx.core.clsp.ClspGraph;
    27	import jadx.core.dex.info.ClassInfo;
    28	import jadx.core.dex.info.ConstStorage;
    29	import jadx.core.dex.info.FieldInfo;
    30	import jadx.core.dex.info.InfoStorage;
    31	import jadx.core.dex.info.MethodInfo;
    32	import jadx.core.dex.instructions.args.ArgType;
    33	import jadx.core.dex.nodes.utils.MethodUtils;
    34	import jadx.core.dex.nodes.utils.TypeUtils;
    35	import jadx.core.dex.visitors.DepthTraversal;
    36	import jadx.core.dex.visitors.IDexTreeVisitor;
    37	import jadx.core.dex.visitors.typeinference.TypeCompare;
    38	import jadx.core.dex.visitors.typeinference.TypeUpdate;
    39	import jadx.core.utils.CacheStorage;
    40	import jadx.core.utils.ErrorsCounter;
    41	import jadx.core.utils.StringUtils;
    42	import jadx.core.utils.Utils;
    43	import jadx.core.utils.android.AndroidResourcesUtils;
    44	import jadx.core.utils.exceptions.JadxRuntimeException;
    45	import jadx.core.xmlgen.IResParser;
    46	import jadx.core.xmlgen.ResDecoder;
    47	import jadx.core.xmlgen.ResourceStorage;
    48	import jadx.core.xmlgen.entry.ResourceEntry;
    49	import jadx.core.xmlgen.entry.ValuesParser;
    50	
    51	public class RootNode {
    52		private static final Logger LOG = LoggerFactory.getLogger(RootNode.class);
    53	
    54		private final JadxArgs args;
    55		private final List<IDexTreeVisitor> preDecompilePasses;
    56		private final List<ICodeDataUpdateListener> codeDataUpdateListeners = new ArrayList<>();
    57	
    58		private final ProcessClass processClasses;
    59		private final ErrorsCounter errorsCounter = new ErrorsCounter();
    60		private final StringUtils stringUtils;
    61		private final ConstStorage constValues;
    62		private final InfoStorage infoStorage = new InfoStorage();
    63		private final CacheStorage cacheStorage = new CacheStorage();
    64		private final TypeUpdate typeUpdate;
    65		private final MethodUtils methodUtils;
    66		private final TypeUtils typeUtils;
    67	
    68		private final Map<ClassInfo, ClassNode> clsMap = new HashMap<>();
    69		private List<ClassNode> classes = new ArrayList<>();
    70	
    71		@Nullable
    72		private ClspGraph clsp;
    73		@Nullable
    74		private String appPackage;
    75		@Nullable
    76		private ClassNode appResClass;
    77		private boolean isProto;
    78	
    79		public RootNode(JadxArgs args) {
    80			this.args = args;
    81			this.preDecompilePasses = Jadx.getPreDecompilePassesList();
    82			this.processClasses = new ProcessClass(this.getArgs());
    83			this.stringUtils = new StringUtils(args);
    84			this.constValues = new ConstStorage(args);
    85			this.typeUpdate = new TypeUpdate(this);
    86			this.methodUtils = new MethodUtils(this);
    87			this.typeUtils = new TypeUtils(this);
    88			this.isProto = args.getInputFiles().size() > 0 && args.getInputFiles().get(0).getName().toLowerCase().endsWith(".aab");
    89		}
    90	
    91		public void loadClasses(List<ILoadResult> loadedInputs) {
    92			for (ILoadResult loadedInput : loadedInputs) {
    93				loadedInput.visitClasses(cls -> {
    94					try {
    95						addClassNode(new ClassNode(RootNode.this, cls));
    96					} catch (Exception e) {
    97						addDummyClass(cls, e);
    98					}
    99					Utils.checkThreadInterrupt();
   100				});
   101			}
   102			if (classes.size() != clsMap.size()) {
   103				// class name duplication detected
   104				markDuplicatedClasses(classes);
   105			}
   106			classes = new ArrayList<>(clsMap.values());
   107	
   108			// print stats for loaded classes
   109			int mthCount = classes.stream().mapToInt(c -> c.getMethods().size()).sum();
   110			int insnsCount = classes.stream().flatMap(c -> c.getMethods().stream()).mapToInt(MethodNode::getInsnsCount).sum();
   111			LOG.info("Loaded classes: {}, methods: {}, instructions: {}", classes.size(), mthCount, insnsCount);
   112	
   113			// sort classes by name, expect top classes before inner
   114			classes.sort(Comparator.comparing(ClassNode::getFullName));
   115			// move inner classes
   116			initInnerClasses();
   117		}
   118	
   119		private void addDummyClass(IClassData classData, Exception exc) {
   120			try {
   121				String typeStr = classData.getType();
   122				String name = null;
   123				try {
   124					ClassInfo clsInfo = ClassInfo.fromName(this, typeStr);
   125					if (clsInfo != null) {
   126						name = clsInfo.getShortName();
   127					}
   128				} catch (Exception e) {
   129					LOG.error("Failed to get name for class with type {}", typeStr, e);
   130				}
   131				if (name == null || name.isEmpty()) {
   132					name = "CLASS_" + typeStr;
   133				}
   134				ClassNode clsNode = ClassNode.addSyntheticClass(this, name, classData.getAccessFlags());
   135				ErrorsCounter.error(clsNode, "Load error", exc);
   136			} catch (Exception innerExc) {
   137				LOG.error("Failed to load class from file: {}", classData.getInputFileName(), exc);
   138			}
   139		}
   140	
   141		private static void markDuplicatedClasses(List<ClassNode> classes) {
   142			classes.stream()
   143					.collect(Collectors.groupingBy(ClassNode::getClassInfo))
   144					.entrySet()
   145					.stream()
   146					.filter(entry -> entry.getValue().size() > 1)
   147					.forEach(entry -> {
   148						List<String> sources = Utils.collectionMap(entry.getValue(), ClassNode::getInputFileName);
   149						LOG.warn("Found duplicated class: {}, count: {}. Only one will be loaded!\n  {}",
   150								entry.getKey(), entry.getValue().size(), String.join("\n  ", sources));
   151						entry.getValue().forEach(cls -> {
   152							String thisSource = cls.getInputFileName();
   153							String otherSourceStr = sources.stream()
   154									.filter(s -> !s.equals(thisSource))
   155									.sorted()
   156									.collect(Collectors.joining("\n  "));
   157							cls.addWarnComment("Classes with same name are omitted:\n  " + otherSourceStr + '\n');
   158						});
   159					});
   160		}
   161	
   162		public void addClassNode(ClassNode clsNode) {
   163			classes.add(clsNode);
   164			clsMap.put(clsNode.getClassInfo(), clsNode);
   165		}
   166	
   167		public void loadResources(List<ResourceFile> resources) {
   168			ResourceFile arsc = getResourceFile(resources);
   169			if (arsc == null) {
   170				LOG.debug("'.arsc' file not found");
   171				return;
   172			}
   173			try {
   174				IResParser parser = ResourcesLoader.decodeStream(arsc, (size, is) -> ResDecoder.decode(this, arsc, is));
   175				if (parser != null) {
   176					processResources(parser.getResStorage());
   177					updateObfuscatedFiles(parser, resources);
   178				}
   179			} catch (Exception e) {
   180				LOG.error("Failed to parse '.arsc' file", e);
   181			}
   182		}
   183	
   184		private @Nullable ResourceFile getResourceFile(List<ResourceFile> resources) {
   185			for (ResourceFile rf : resources) {
   186				if (rf.getType() == ResourceType.ARSC) {
   187					return rf;
   188				}
   189			}
   190			return null;
   191		}
   192	
   193		public void processResources(ResourceStorage resStorage) {
   194			constValues.setResourcesNames(resStorage.getResourcesNames());
   195			appPackage = resStorage.getAppPackage();
   196			appResClass = AndroidResourcesUtils.searchAppResClass(this, resStorage);
   197		}
   198	
   199		public void initClassPath() {
   200			try {
   201				if (this.clsp == null) {
   202					ClspGraph newClsp = new ClspGraph(this);
   203					newClsp.load();
   204					newClsp.addApp(classes);
   205					newClsp.initCache();
   206					this.clsp = newClsp;
   207				}
   208			} catch (Exception e) {
   209				throw new JadxRuntimeException("Error loading jadx class set", e);
   210			}
   211		}
   212	
   213		private void updateObfuscatedFiles(IResParser parser, List<ResourceFile> resources) {
   214			if (args.isSkipResources()) {
   215				return;
   216			}
   217			long start = System.currentTimeMillis();
   218			int renamedCount = 0;
   219			ResourceStorage resStorage = parser.getResStorage();
   220			ValuesParser valuesParser = new ValuesParser(parser.getStrings(), resStorage.getResourcesNames());
   221			Map<String, ResourceEntry> entryNames = new HashMap<>();
   222			for (ResourceEntry resEntry : resStorage.getResources()) {
   223				String val = valuesParser.getSimpleValueString(resEntry);
   224				if (val != null) {
   225					entryNames.put(val, resEntry);
   226				}
   227			}
   228			for (ResourceFile resource : resources) {
   229				ResourceEntry resEntry = entryNames.get(resource.getOriginalName());
   230				if (resEntry != null) {
   231					resource.setAlias(resEntry);
   232					renamedCount++;
   233				}
   234			}
   235			if (LOG.isDebugEnabled()) {
   236				LOG.debug("Renamed obfuscated resources: {}, duration: {}ms", renamedCount, System.currentTimeMillis() - start);
   237			}
   238		}
   239	
   240		private void initInnerClasses() {
   241			// move inner classes
   242			List<ClassNode> inner = new ArrayList<>();
   243			for (ClassNode cls : classes) {
   244				if (cls.getClassInfo().isInner()) {
   245					inner.add(cls);
   246				}
   247			}
   248			List<ClassNode> updated = new ArrayList<>();
   249			for (ClassNode cls : inner) {
   250				ClassInfo clsInfo = cls.getClassInfo();
   251				ClassNode parent = resolveClass(clsInfo.getParentClass());
   252				if (parent == null) {
   253					clsMap.remove(clsInfo);
   254					clsInfo.notInner(this);
   255					clsMap.put(clsInfo, cls);
   256					updated.add(cls);
   257				} else {
   258					parent.addInnerClass(cls);
   259				}
   260			}
   261			// reload names for inner classes of updated parents
   262			for (ClassNode updCls : updated) {
   263				for (ClassNode innerCls : updCls.getInnerClasses()) {
   264					innerCls.getClassInfo().updateNames(this);
   265				}
   266			}
   267			classes.forEach(ClassNode::updateParentClass);
   268		}
   269	
   270		public void runPreDecompileStage() {
   271			boolean debugEnabled = LOG.isDebugEnabled();
   272			for (IDexTreeVisitor pass : preDecompilePasses) {
   273				Utils.checkThreadInterrupt();
   274				long start = debugEnabled ? System.currentTimeMillis() : 0;
   275				try {
   276					pass.init(this);
   277				} catch (Exception e) {
   278					LOG.error("Visitor init failed: {}", pass.getClass().getSimpleName(), e);
   279				}
   280				for (ClassNode cls : classes) {
   281					if (cls.isInner()) {
   282						continue;
   283					}
   284					DepthTraversal.visit(pass, cls);
   285				}
   286				if (debugEnabled) {
   287					LOG.debug("{} time: {}ms", pass.getClass().getSimpleName(), System.currentTimeMillis() - start);
   288				}
   289			}
   290		}
   291	
   292		public void runPreDecompileStageForClass(ClassNode cls) {
   293			for (IDexTreeVisitor pass : preDecompilePasses) {
   294				DepthTraversal.visit(pass, cls);
   295			}
   296		}
   297	
   298		public List<ClassNode> getClasses() {
   299			return classes;
   300		}
   301	
   302		public List<ClassNode> getClassesWithoutInner() {
   303			return getClasses(false);
   304		}
   305	
   306		public List<ClassNode> getClasses(boolean includeInner) {
   307			if (includeInner) {
   308				return classes;
   309			}
   310			List<ClassNode> notInnerClasses = new ArrayList<>();
   311			for (ClassNode cls : classes) {
   312				if (!cls.getClassInfo().isInner()) {
   313					notInnerClasses.add(cls);
   314				}
   315			}
   316			return notInnerClasses;
   317		}
   318	
   319		@Nullable
   320		public ClassNode resolveClass(@Nullable ClassInfo clsInfo) {
   321			return clsMap.get(clsInfo);
   322		}
   323	
   324		@Nullable
   325		public ClassNode resolveClass(@Nullable ArgType clsType) {
   326			if (!clsType.isTypeKnown() || clsType.isGenericType()) {
   327				return null;
   328			}
   329			if (clsType.getWildcardBound() == ArgType.WildcardBound.UNBOUND) {
   330				return null;
   331			}
   332			if (clsType.isGeneric()) {
   333				clsType = ArgType.object(clsType.getObject());
   334			}
   335			return resolveClass(ClassInfo.fromType(this, clsType));
   336		}
   337	
   338		@Nullable
   339		public ClassNode resolveClass(String fullName) {
   340			ClassInfo clsInfo = ClassInfo.fromName(this, fullName);
   341			return resolveClass(clsInfo);
   342		}
   343	
   344		/**
   345		 * Searches for ClassNode by its full name (original or alias name)
   346		 * <br>
   347		 * Warning: This method has a runtime of O(n) (n = number of classes).
   348		 * If you need to call it more than once consider {@link #buildFullAliasClassCache()} instead
   349		 */
   350		@Nullable
   351		public ClassNode searchClassByFullAlias(String fullName) {
   352			for (ClassNode cls : classes) {
   353				ClassInfo classInfo = cls.getClassInfo();
   354				if (classInfo.getFullName().equals(fullName)
   355						|| classInfo.getAliasFullName().equals(fullName)) {
   356					return cls;
   357				}
   358			}
   359			return null;
   360		}
   361	
   362		public Map<String, ClassNode> buildFullAliasClassCache() {
   363			Map<String, ClassNode> classNameCache = new HashMap<>(classes.size());
   364			for (ClassNode cls : classes) {
   365				ClassInfo classInfo = cls.getClassInfo();
   366				String fullName = classInfo.getFullName();
   367				String alias = classInfo.getAliasFullName();
   368				classNameCache.put(fullName, cls);
   369				if (alias != null && !fullName.equals(alias)) {
   370					classNameCache.put(alias, cls);
   371				}
   372			}
   373			return classNameCache;
   374		}
   375	
   376		public List<ClassNode> searchClassByShortName(String shortName) {
   377			List<ClassNode> list = new ArrayList<>();
   378			for (ClassNode cls : classes) {
   379				if (cls.getClassInfo().getShortName().equals(shortName)) {
   380					list.add(cls);
   381				}
   382			}
   383			return list;
   384		}
   385	
   386		@Nullable
   387		public MethodNode resolveMethod(@NotNull MethodInfo mth) {
   388			ClassNode cls = resolveClass(mth.getDeclClass());
   389			if (cls == null) {
   390				return null;
   391			}
   392			MethodNode methodNode = cls.searchMethod(mth);
   393			if (methodNode != null) {
   394				return methodNode;
   395			}
   396			return deepResolveMethod(cls, mth.makeSignature(false));
   397		}
   398	
   399		@Nullable
   400		private MethodNode deepResolveMethod(@NotNull ClassNode cls, String signature) {
   401			for (MethodNode m : cls.getMethods()) {
   402				if (m.getMethodInfo().getShortId().startsWith(signature)) {
   403					return m;
   404				}
   405			}
   406			MethodNode found;
   407			ArgType superClass = cls.getSuperClass();
   408			if (superClass != null) {
   409				ClassNode superNode = resolveClass(superClass);
   410				if (superNode != null) {
   411					found = deepResolveMethod(superNode, signature);
   412					if (found != null) {
   413						return found;
   414					}
   415				}
   416			}
   417			for (ArgType iFaceType : cls.getInterfaces()) {
   418				ClassNode iFaceNode = resolveClass(iFaceType);
   419				if (iFaceNode != null) {
   420					found = deepResolveMethod(iFaceNode, signature);
   421					if (found != null) {
   422						return found;
   423					}
   424				}
   425			}
   426			return null;
   427		}
   428	
   429		@Nullable
   430		public FieldNode resolveField(FieldInfo field) {
   431			ClassNode cls = resolveClass(field.getDeclClass());
   432			if (cls == null) {
   433				return null;
   434			}
   435			FieldNode fieldNode = cls.searchField(field);
   436			if (fieldNode != null) {
   437				return fieldNode;
   438			}
   439			return deepResolveField(cls, field);
   440		}
   441	
   442		@Nullable
   443		private FieldNode deepResolveField(@NotNull ClassNode cls, FieldInfo fieldInfo) {
   444			FieldNode field = cls.searchFieldByNameAndType(fieldInfo);
   445			if (field != null) {
   446				return field;
   447			}
   448			ArgType superClass = cls.getSuperClass();
   449			if (superClass != null) {
   450				ClassNode superNode = resolveClass(superClass);
   451				if (superNode != null) {
   452					FieldNode found = deepResolveField(superNode, fieldInfo);
   453					if (found != null) {
   454						return found;
   455					}
   456				}
   457			}
   458			for (ArgType iFaceType : cls.getInterfaces()) {
   459				ClassNode iFaceNode = resolveClass(iFaceType);
   460				if (iFaceNode != null) {
   461					FieldNode found = deepResolveField(iFaceNode, fieldInfo);
   462					if (found != null) {
   463						return found;
   464					}
   465				}
   466			}
   467			return null;
   468		}
   469	
   470		public ProcessClass getProcessClasses() {
   471			return processClasses;
   472		}
   473	
   474		public List<IDexTreeVisitor> getPasses() {
   475			return processClasses.getPasses();
   476		}
   477	
   478		public void initPasses() {
   479			processClasses.initPasses(this);
   480		}
   481	
   482		public ICodeWriter makeCodeWriter() {
   483			JadxArgs jadxArgs = this.args;
   484			return jadxArgs.getCodeWriterProvider().apply(jadxArgs);
   485		}
   486	
	public void registerCodeDataUpdateListener(ICodeDataUpdateListener listener) {
		this.codeDataUpdateListeners.add(listener);
	}

	public void notifyCodeDataListeners() {
		ICodeData codeData = args.getCodeData();
		codeDataUpdateListeners.forEach(l -> l.updated(codeData));
	}

	public ClspGraph getClsp() {
		if (clsp == null) {
			initClassPath();
		}
		return clsp;
	}

	public ErrorsCounter getErrorsCounter() {
		return errorsCounter;
	}

	@Nullable
	public String getAppPackage() {
		return appPackage;
	}

	@Nullable
	public ClassNode getAppResClass() {
		return appResClass;
	}

	public StringUtils getStringUtils() {
		return stringUtils;
	}

	public ConstStorage getConstValues() {
		return constValues;
	}

	public InfoStorage getInfoStorage() {
		return infoStorage;
	}

	public CacheStorage getCacheStorage() {
		return cacheStorage;
	}

	public JadxArgs getArgs() {
		return args;
	}

	public TypeUpdate getTypeUpdate() {
		return typeUpdate;
	}

	public TypeCompare getTypeCompare() {
		return typeUpdate.getTypeCompare();
	}

	public ICodeCache getCodeCache() {
		return args.getCodeCache();
	}

	public MethodUtils getMethodUtils() {
		return methodUtils;
	}

	public TypeUtils getTypeUtils() {
		return typeUtils;
	}

	public boolean isProto() {
		return isProto;
	}
}
