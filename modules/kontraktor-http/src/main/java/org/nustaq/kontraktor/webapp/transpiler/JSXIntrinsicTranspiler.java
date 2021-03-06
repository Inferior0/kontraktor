package org.nustaq.kontraktor.webapp.transpiler;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import io.undertow.server.handlers.resource.Resource;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.util.Log;
import org.nustaq.kontraktor.webapp.javascript.DynamicResourceManager;
import org.nustaq.kontraktor.webapp.javascript.FileResolver;
import org.nustaq.kontraktor.webapp.npm.JNPM;
import org.nustaq.kontraktor.webapp.npm.JNPMConfig;
import org.nustaq.kontraktor.webapp.transpiler.jsx.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * transpiles jsx without requiring babel.
 */
public class JSXIntrinsicTranspiler implements TranspilerHook {

    public static boolean BUNDLE_NODE_ALWAYS = true;

    protected boolean dev;
    protected File jnpmNodeModulesDir;
    protected boolean autoJNPM;
    protected JNPMConfig jnpmConfig;
    protected String jnpmConfigFile;
    protected JNPMConfig jnpmConfigFileCached;
    protected List<WatchedFile> readFiles;
    protected Map<String,File> nodeTopLevelImports;
    protected FileWatcher watcher;
    protected TimeStampedFileCache<JSXGenerator.ParseResult> transpiledCache = new TimeStampedFileCache();
    protected Map<String,File> nodeDirResolveCache = new HashMap<>();
    protected boolean hmr = false;
    protected String globalPrologue = ""; // injected at top level of index page

    public JSXIntrinsicTranspiler(boolean dev) {
        this.dev = dev;
        this.autoJNPM = dev; // use fluent setter to turn off also for dev
    }

    @Override
    public byte[] transpile(File f) throws TranspileException {
        throw new RuntimeException("should not be called");
    }

    public JSXIntrinsicTranspiler globalPrologue(final String globalPrologue) {
        this.globalPrologue = globalPrologue;
        return this;
    }

    public String getGlobalPrologue() {
        return globalPrologue;
    }

    @Override
    public byte[] transpile(File f, FileResolver resolver, Map<String,Object> alreadyResolved) {
        byte[] bytes = processJSX(dev, f, resolver, alreadyResolved);
        return bytes;
    }

    private NodeLibNameResolver createNodeLibNameResolver(FileResolver resolver) {
        return new NodeLibNameResolver() {
            @Override
            public String getFinalLibName(File requiredIn, FileResolver res, String requireText) {
                File file = null;
                try {
                    file = findNodeModulesNearestMatch(requiredIn,requireText);
                    if ( file == null )
                        file = resolver.resolveFile(requiredIn.getParentFile(), requireText);
                    if ( file == null )
                        file = resolver.resolveFile(requiredIn.getParentFile(), requireText+".js");
                    if ( file == null )
                        file = resolver.resolveFile(requiredIn.getParentFile(), requireText+".jsx");
                    if ( file == null ) {
                        Log.Warn(this,"unable to find finalLibName for:"+requireText+" in "+requiredIn.getAbsolutePath());
                        return requireText;
                    }
                    if ( file.isDirectory() )
                        file = processNodeDir(file,resolver,new HashMap());
                    return constructLibName(file,resolver);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            public byte[] resolve(File baseDir, String name, Map<String, Object> alreadyProcessed) {
                return resolver.resolve(baseDir,name,alreadyProcessed);
            }

            @Override
            public File resolveFile(File baseDir, String name) {
                return resolver.resolveFile(baseDir,name);
            }

            @Override
            public void install(String path, byte[] resolved) {
                resolver.install(path,resolved);
            }

            @Override
            public String resolveUniquePath(File file) {
                return resolver.resolveUniquePath(file);
            }

            @Override
            public Resource getResource(String initialPath) {
                return resolver.getResource(initialPath);
            }
        };
    }

    static File falseFile = new File("false");

    /**
     * return first subdirectory of nearest node path. e.. ../node_modules/react for ../node_modules/dist/lib/index.js
     * @param requiringFile
     * @param requireText
     * @return
     * @throws IOException
     */
    File findNodeModulesNearestMatch(File requiringFile, String requireText) throws IOException {
        if ( requiringFile == null )
            return null;
        if ( !requireText.startsWith(".") ) {
            File f = new File(requiringFile, "node_modules/" + requireText);
            if (f.exists())
                return new File(TimeStampedFileCache.getCanonicalPath(f));
            f = new File(requiringFile, "node_modules/" + requireText + ".js");
            if (f.exists())
                return new File(TimeStampedFileCache.getCanonicalPath(f));
            return findNodeModulesNearestMatch(requiringFile.getParentFile(), requireText);
        } else {
            File f = new File(requiringFile.getParentFile(),requireText);
            if ( ! f.exists() )
                f =  new File(requiringFile.getParentFile(),requireText+".js");
            if ( f.exists() )
                return new File(TimeStampedFileCache.getCanonicalPath(f));
        }
        return null;
    }

    private String getCanonicalPath(File f) throws IOException {
        if ( Files.isSymbolicLink(f.toPath()) )
            return f.getAbsolutePath();
        return f.getCanonicalPath();
    }

    String findNodeSubDir(File requiringFile) throws IOException {
        if ( requiringFile == null )
            return null;
        if ( requiringFile.getParentFile() != null && requiringFile.getParentFile().getName().equals("node_modules") )
            return TimeStampedFileCache.getCanonicalPath(requiringFile);
        else
            return findNodeSubDir(requiringFile.getParentFile());
    }

    private File processNodeDir(File file, FileResolver resolver, Map<String, Object> alreadyResolved) {
        File jfi = new File(file, "package.json");
        if ( jfi.exists() ) {
            try {
                JsonObject pkg = Json.parse(new FileReader(jfi)).asObject();
                JsonValue browser = pkg.get("browser");
                if ( browser != null ) {
                    if (browser.isBoolean() && !browser.asBoolean() ) {
                        return falseFile;
                    }
                    if ( browser.isString() ) {
//                        Log.Info(this,"package.json browser entry map to "+browser.asString());
                        return new File(file,browser.asString());
                    }
                    if ( browser.isObject() ) {
                        String nodeModuleDir = TimeStampedFileCache.getCanonicalPath(file);
                        JsonObject members = browser.asObject();
                        members.forEach( member -> {
                            String key = "browser_" + nodeModuleDir + "_" + member.getName();
                            alreadyResolved.put(key, member.getValue());
//                            System.out.println("put browser:"+key);
//                            System.out.println("  val:"+member.getValue());
                        });
                    } else {
                        Log.Warn(this, "unrecognized 'browser' entry in package.json, " + TimeStampedFileCache.getCanonicalPath(file));
                        return null;
                    }
                }

                String main = pkg.getString("main", null);
                if ( main != null ) {
                    if ( ! main.endsWith(".js") )
                        main = main+".js"; // omg
                    File newF = new File(file, main);
                    return newF;
                }
                File indexf = new File(file, "index.js");
                if ( indexf.exists() ) {
                    return indexf;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if ( new File(file,"index.js").exists() ) {
            return new File(file,"index.js");
        } else if ( new File(file.getParentFile(),file.getName()+".js").exists() ) {
            return new File(file.getParentFile(),file.getName()+".js");
        }
        return null;
    }

    public byte[] updateJSX(File f, FileResolver resolver) {
        try {
            boolean dev = true;
            ErrorHandler.get().reset();
            JSXGenerator.ParseResult result = JSXGenerator.process(f,dev,createNodeLibNameResolver(resolver),getConfig());
            byte[] res = result.getFiledata();
            ByteArrayOutputStream mainBao = new ByteArrayOutputStream(20_000);
            if (result.generateESWrap())
                mainBao.write(generateImportPrologue(result, resolver).getBytes("UTF-8"));
            if (result.generateCommonJSWrap())
                mainBao.write(generateCommonJSPrologue(f,result, resolver).getBytes("UTF-8"));
            mainBao.write(res);
            if (result.generateESWrap())
                mainBao.write(generateImportEnd(result, resolver).getBytes("UTF-8"));
            if (result.generateCommonJSWrap())
                mainBao.write(generateCommonJSEnd(f,result, resolver).getBytes("UTF-8"));

            String dirName = "_node_modules";
            if (isNotInNodeModules(f)) {
                dirName = "_appsrc";
            }
            String name = constructLibName(f, resolver) + ".transpiled";
            resolver.install("/"+dirName+"/" + name, mainBao.toByteArray());
            return mainBao.toByteArray();
        } catch (Exception e) {
            Log.Error(this,e);
            StringWriter out = new StringWriter();
            e.printStackTrace(new PrintWriter(out));
            try {
                return out.getBuffer().toString().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();
            }
        }
        return new byte[0];
    }

    protected byte[] processJSX(boolean dev, File f, FileResolver resolver, Map<String, Object> alreadyResolved) {
        try {
            boolean isInitialIndexJSX = f != null && f.getName().endsWith("index.jsx");
            if ( isInitialIndexJSX ) {
                ErrorHandler.get().reset();
                if ( ParseUtils.CountWords )
                    ParseUtils.WordCount.clear();
                jnpmConfigFileCached = null;
                if ( dev ) {
                    readFiles = new ArrayList<>();
                    nodeTopLevelImports = new HashMap<>();
                    if ( watcher != null ) {
//                        watcher.stopWatching(); is now singleton
                        watcher = null;
                    }
                }
            }
            NodeLibNameResolver nodeLibNameResolver = createNodeLibNameResolver(resolver);
            JSXGenerator.ParseResult result = null;
            if ( dev && !isInitialIndexJSX ) {
                result = transpiledCache.get(f);
            }
            if ( result == null ) {
                result = JSXGenerator.process(f, dev, nodeLibNameResolver, getConfig());
                if (dev && !isInitialIndexJSX)
                    transpiledCache.put(f, result);
            }
            boolean notInNodeModules = isNotInNodeModules(f);
            boolean notInNodeModulesForModuleBundlingDev = notInNodeModules || !BUNDLE_NODE_ALWAYS;
            if ( dev ) {
                if (notInNodeModules) {
                    String finalLibName = nodeLibNameResolver.getFinalLibName(f, resolver, f.getName() /*FIXME: ??*/);
                    readFiles.add(new WatchedFile(f, this, resolver, finalLibName));
                }
            }
            List<ImportSpec> specs = result.getImports();
            byte[] res = result.getFiledata();
            if (isInitialIndexJSX) {
                alreadyResolved.put("JSXIndexStart", System.currentTimeMillis());
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1_000_000);
                baos.write((getInitialShims()+"\n").getBytes("UTF-8"));
                alreadyResolved.put("JSXIndex", baos);
            }
            ByteArrayOutputStream indexBaos = (ByteArrayOutputStream) alreadyResolved.get("JSXIndex");
            if ( alreadyResolved.get("_Ignored") == null ) {
                alreadyResolved.put("_Ignored",result.getIgnoredRequires());
            }
            Set ignoredRequires = (Set) alreadyResolved.get("_Ignored");
            ignoredRequires.addAll(result.getIgnoredRequires());

            for (int i = 0; i < specs.size(); i++) {
                ImportSpec importSpec = specs.get(i);
                File redirected = resolveImportSpec(f, importSpec, resolver, alreadyResolved, ignoredRequires);
                if (redirected == null) continue;
            }
            ByteArrayOutputStream mainBao = dev && notInNodeModulesForModuleBundlingDev  ? new ByteArrayOutputStream(20_000) : indexBaos;
            if (result.generateESWrap())
                mainBao.write(generateImportPrologue(result, resolver).getBytes("UTF-8"));
            if (result.generateCommonJSWrap())
                mainBao.write(generateCommonJSPrologue(f,result, resolver).getBytes("UTF-8"));
            mainBao.write(res);
            if (result.generateESWrap())
                mainBao.write(generateImportEnd(result, resolver).getBytes("UTF-8"));
            if (result.generateCommonJSWrap())
                mainBao.write(generateCommonJSEnd(f,result, resolver).getBytes("UTF-8"));

            if ( dev && notInNodeModulesForModuleBundlingDev ) {
                String dirName = "_node_modules";
                if (notInNodeModules) {
                    dirName = "_appsrc";
                }
                String name = constructLibName(f, resolver) + ".transpiled";
                resolver.install("/"+dirName+"/" + name, mainBao.toByteArray());
                indexBaos.write(
                    ("document.write( '<script src=\""+dirName+"/" + name + "\"></script>');\n")
                        .getBytes("UTF-8")
                );
            }
            if (isInitialIndexJSX) {
                if ( dev ) {
                    indexBaos.write("document.write('<script>_kreporterr = true; kinitfuns.forEach( fun => fun() );</script>')\n".getBytes("UTF-8"));
                    watcher = FileWatcher.get();
                    watcher.setFiles(readFiles);

                    if ( dev && getConfig().isGeneratePackageDotJson() ) {
                        System.out.println("============================= TOP LEVEL IMPORTS ======================================");
                        nodeTopLevelImports.forEach( (s,fi) -> {
                            try {
                                File file = new File(fi, "package.json");
                                if ( file.exists() ) {
                                    JsonValue packjson = Json.parse(new FileReader(file));
                                    String version = packjson.asObject().getString("version", "*");
                                    System.out.println("\"" + s + "\":" + "\"" + version + "\",");
                                } else {
                                    System.out.println("\"" + s + "\":" + "no package.json");
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });

                    }
                }
                else
                    indexBaos.write( "_kreporterr = true; kinitfuns.forEach( fun => fun() );\n".getBytes("UTF-8"));
                Long tim = (Long) alreadyResolved.get("JSXIndexStart");
                Log.Info(this, "Transpilation time:"+(System.currentTimeMillis()-tim)/1000.0);
                if ( isInitialIndexJSX && dev ) {
                    List<String> errors = ErrorHandler.get().getErrors();
                    for (int i = 0; i < errors.size(); i++) {
                        String s = errors.get(i);
                        indexBaos.write(("console.warn('"+s+"');\n").getBytes("UTF-8"));
                    }
                    Log.Info(this, "Number of Words estimated "+ParseUtils.calcWordCount() );
                }
                return indexBaos.toByteArray();
            }
            return mainBao.toByteArray();
        } catch (Exception e) {
            Log.Error(this,e);
            StringWriter out = new StringWriter();
            e.printStackTrace(new PrintWriter(out));
            try {
                return out.getBuffer().toString().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();
            }
        }
        return new byte[0];
    }

    public static boolean isNotInNodeModules(File f) {
        return f.getAbsolutePath().replace('\\','/').indexOf("/node_modules/") < 0;
    }

    private File resolveImportSpec(File requiringFile, ImportSpec importSpec, FileResolver resolver, Map<String, Object> alreadyResolved, Set ignoredRequires) throws IOException {
        String from = importSpec.getFrom();
        File toReadFrom = requiringFile;
        String toReadFromName = null; // node package entry processing
        if ( importSpec.isRequire() ) {
            if (ignoredRequires.contains(importSpec.getFrom()) )
                return null;
            if ( dev && getConfig().getIgnoredDevRequires().contains(importSpec.getFrom())) {
                Log.Info(this,"omit "+importSpec.getFrom()+" caused by jnpm.kson");
                return null;
            }
            if ( !dev && getConfig().getIgnoredProdRequires().contains(importSpec.getFrom())) {
                Log.Info(this, "omit " + importSpec.getFrom() + " caused by jnpm.kson");
                return null;
            }

            String canonicalF = findNodeSubDir(requiringFile);
            if ( canonicalF != null ) {
                // check for ignored requires in browser entry of package.json
                String key = "browser_" + canonicalF + "_" + from;
                JsonValue o = (JsonValue) alreadyResolved.get(key);
                if (o != null) {
                    if (o.isString()) {
                        String oldFrom = from;
                        from = o.asString();
                        Log.Info(this,"mapping package.json/browser:"+oldFrom+" to "+from);
                    } else if (o.isBoolean()) {
                        if (!o.asBoolean()) {
                            Log.Info(this,"ignoring because of package.json/browser:"+from);
                            return null;
                        }
                    } else
                        Log.Warn(this, "unrecognized browser entry in package.json:" + o + ". file:" + requiringFile.getAbsolutePath());
                } else {
//                            System.out.println("key lookup == null for browser setting :"+key);
                }
            } else {
                Log.Warn(this, "node module dir could not be resolved " + requiringFile.getAbsolutePath());
                return null;
            }
        }
        File resolvedFile;
        if (importSpec.isRequire() ) {
            String key = requiringFile.getAbsolutePath() + "#" + from;
            resolvedFile = nodeDirResolveCache.get(key);
            if ( resolvedFile == null ) {
                resolvedFile = findNodeModulesNearestMatch(requiringFile, from);
                if ( resolvedFile != null )
                    nodeDirResolveCache.put(key,resolvedFile);
            }
            if ( resolvedFile != null ) {
                toReadFromName = resolvedFile.getName();
                toReadFrom = resolvedFile;
            } else {
                int debug = 1;
            }
        } else {
            resolvedFile = resolver.resolveFile(requiringFile.getParentFile(), from);
        }
        if ( resolvedFile != null && resolvedFile.isDirectory() ) {

            if ( isNotInNodeModules(requiringFile) )
            {
                String tlFrom = importSpec.getFrom();
                if ( nodeTopLevelImports != null )
                    nodeTopLevelImports.put(tlFrom,resolvedFile);
            }

            File indexFile = processNodeDir(resolvedFile, resolver, alreadyResolved);
            if ( indexFile == falseFile ) {
                return null;
            }
            if ( indexFile == null )
            {
                ErrorHandler.get().add(this.getClass(),"node directory could not be resolved to a resource ",resolvedFile);
                return null;
            } else {
                toReadFrom = indexFile;
                toReadFromName = indexFile.getName();
            }
        } else {
            int pointIdx = from.lastIndexOf('.');
            int extlen = from.length() - pointIdx;
            int beginIndex = from.length() - extlen;
            if ( pointIdx >= 0 && extlen < 6 && from.substring(beginIndex).indexOf('/') < 0) {
               // hasExtension (now generally catched) [might have sideeffects see line below old code]
            } else if (!from.endsWith(".js") && !from.endsWith(".jsx") && !from.endsWith(".json")) { // [old hack] auto add missing extension
                from += ".js";
            }
        }
        byte resolved[] = resolver.resolve(toReadFrom.getParentFile(), toReadFromName != null ? toReadFromName : from, alreadyResolved);
        if ( resolved == null && from.endsWith(".js") ) {
            // try jsx
            from = from.substring(0,from.length()-3)+".jsx";
            resolved = resolver.resolve(requiringFile.getParentFile(), from, alreadyResolved);
        }
        if ( resolved != null ) {
            if ( resolved.length > 0 ) {
                // need re-resolve as extension might have changed
                resolvedFile = resolver.resolveFile(toReadFrom.getParentFile(),toReadFromName != null ? toReadFromName : from);
                String name = null;
                if ( resolvedFile.getName().endsWith(".json") ) {
                    name = constructLibName(resolvedFile, resolver) + ".json";
                    ByteArrayOutputStream jsonBao = new ByteArrayOutputStream(resolved.length+100);
                    jsonBao.write("(function(exports, require, module, __filename, __dirname) { module.exports = \n".getBytes("UTF-8"));
                    jsonBao.write(resolved);
                    String s = constructLibName(requiringFile, resolver);
                    jsonBao.write(
                        ("})( kgetModule('"+s+"').exports, krequire, kgetModule('"+s+"'), '', '' );").getBytes("UTF-8"));
                    resolver.install("/debug/" + name, jsonBao.toByteArray());
                } else if ( resolvedFile.getName().endsWith(".css") && importSpec.isPureImport() ) { // support direct import of css
                    name = constructLibName(resolvedFile, resolver);
                    ByteArrayOutputStream cssBao = new ByteArrayOutputStream(resolved.length+100);
                    cssBao.write(("if ( !window['"+name+"'] ) {\n").getBytes("UTF-8"));
                    cssBao.write("  const __css__ = document.createElement('style');".getBytes("UTF-8"));
                    cssBao.write("  __css__.type = \"text/css\";\n".getBytes("UTF-8"));
                    cssBao.write("  __css__.innerHTML = `".getBytes("UTF-8"));
                    cssBao.write(resolved);
                    cssBao.write("`\n".getBytes("UTF-8"));
                    cssBao.write("  document.body.appendChild(__css__);\n".getBytes("UTF-8"));
                    cssBao.write(("  window['"+name+"'] = 1;\n").getBytes("UTF-8"));
                    cssBao.write("}\n".getBytes("UTF-8"));
                    String s = constructLibName(requiringFile, resolver);
                    resolver.install("/debug/" + name, cssBao.toByteArray());
                    // code is taken out later when generating prologue
                }
            }
        }
        else {
            if ( autoJNPM && jnpmNodeModulesDir != null ) {
                String required = importSpec.getFrom();
                required = getLookupLibName(required);
                if ( required.indexOf(".") < 0 ) // single file can't be a node module
                {
                    JNPMConfig config = getConfig();
                    Log.Info(this, importSpec.getFrom() + " not found. installing .. '" + required+"'");
                    try {
                        JNPM.InstallResult await = JNPM.Install(required, null, jnpmNodeModulesDir, config).await(TimeUnit.MINUTES.toMillis(1));
                        if ( await == JNPM.InstallResult.INSTALLED )
                            return resolveImportSpec(requiringFile, importSpec, resolver, alreadyResolved, ignoredRequires);
                    } catch (Throwable kt) {
                        kt.printStackTrace();
                        Log.Error(this,"jnpm install timed out. Check Proxy JVM settings, internet connectivity or just retry");
                    }
                }
            }
            ErrorHandler.get().add(this.getClass(), importSpec.getFrom() + " not found. requiredBy ",requiringFile);
        }
        return requiringFile;
    }

    public static String getLookupLibName(String required) {
        if ( required.startsWith("@") ) { // deal with scopes by guessing for now
            int idx = required.indexOf('/');
            required = required.substring(0,idx)+"/"+required.substring(idx+1);
            int i = required.indexOf("/",idx+1);
            if (i >= 0) {
                required = required.substring(0, i);
            }
            return required;
        } else {
            int i = required.indexOf("/");
            if (i >= 0) {
                required = required.substring(0, i);
            }
            return required;
        }
    }

    protected JNPMConfig getConfig() {
        return jnpmConfig != null ?
            jnpmConfig
            : (jnpmConfigFile != null ?
            (jnpmConfigFileCached != null ? jnpmConfigFileCached : (jnpmConfigFileCached = JNPMConfig.read(jnpmConfigFile)) )
            : new JNPMConfig()
        );
    }

    protected String generateCommonJSPrologue(File f, JSXGenerator.ParseResult result, FileResolver resolver ) {
        return "(function(exports, require, module, __filename, __dirname) {\n";
    }

    protected String generateCommonJSEnd(File f, JSXGenerator.ParseResult result, FileResolver resolver) {
        String s = constructLibName(f, resolver);
        return "\n})( kgetModule('"+s+"').exports, krequire, kgetModule('"+s+"'), '', '' );";
    }

    protected String generateImportEnd(JSXGenerator.ParseResult result, FileResolver resolver) {
        String s = "\n\n\n//generated by jsxtranspiler\n";
        String libName = constructLibName(result.getFile(), resolver);
        s+= "kimports['" + libName +"']=_kimptmp;";
        String exportObject = "_kimptmp";
        for (int i = 0; i < result.getGlobals().size(); i++) {
            String gl = result.getGlobals().get(i);
            s+=exportObject+"."+gl+" = _kwrapfn("+gl+");";
            if ( gl.equals(result.getDefaultExport())) {
                s+=exportObject+".__kdefault__= "+exportObject+"."+gl;
            }
        }
        if ( hmr ) {
            s += "\n_kimptmp.__modimports = __modimports; _kimptmp.__initial_modimports = __initial_modimports;\n";
        }
        if ( dev && hmr ) {
            s += "\n__keval['" + libName + "'] = __uh_oh__ => {";
            for (int i = 0; i < result.getGlobals().size(); i++) {
                String gl = result.getGlobals().get(i);
                s+="let "+gl+" = _kimptmp['"+gl+"'];";
                if ( gl.equals(result.getDefaultExport())) {
                    s+="let __kdefault__= "+gl+";";
                }
            }
            s += "return eval(__uh_oh__.toString()); }";
            if ( "index".equals(libName) ) {
                s+=getHMRReloadFun();
            }
        }
        return s+"});\n";
    }

    protected String generateImportPrologue(JSXGenerator.ParseResult result, FileResolver resolver) {
        String s = "";
        s += "(new function() {\n";
        List<ImportSpec> imports = result.getImports();
        // declaration
        NodeLibNameResolver nodeLibNameResolver = createNodeLibNameResolver(resolver);
        for (int i = 0; i < imports.size(); i++) {
            ImportSpec spec = imports.get(i);
            String libname = nodeLibNameResolver.getFinalLibName(result.getFile(),resolver,spec.getFrom());
            if ( spec.getAlias() != null ) {
                s+="let "+spec.getAlias()+"=null;";
            }
            for (int j = 0; j < spec.getAliases().size(); j++) {
                String alias = spec.getAliases().get(j);
                s+="let "+alias+"=null;";
            }
        }
        if ( hmr ) {
            s+="\n  const __modimports = { _kNoHMR: true };";
            s+="\n  const __initial_modimports = { _kNoHMR: true };";
        }
        s+="\n  const _initmods = () => {\n";
        for (int i = 0; i < imports.size(); i++) {
            ImportSpec spec = imports.get(i);
            String libname = nodeLibNameResolver.getFinalLibName(result.getFile(),resolver,spec.getFrom());
            if ( spec.isPureImport() ) {
                // assume pure "import 'a/b/xy.css' .. has been put to debug libs => insert generated code in place in module header
                // hacked: install some js generate using resolver.install => then retrieve it here
                byte[] resolved = ((DynamicResourceManager.MyResource)resolver.getResource("/debug/"+libname )).getBytes();
                try {
                    s += new String(resolved,"UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
            if ( spec.getAlias() != null ) {
                s+="    "+spec.getAlias()+" = _kresolve('"+libname+"');"+(hmr ? "": "\n");
                if ( hmr ) {
                    s+="__modimports['"+spec.getAlias()+"'] = "+spec.getAlias()+"; __initial_modimports['"+spec.getAlias()+"'] = true; \n";
                }
            }
            for (int j = 0; j < spec.getAliases().size(); j++) {
                String alias = spec.getAliases().get(j);
                s+="    "+alias+" = _kresolve('"+libname+"', '"+spec.getComponents().get(j)+"'); "+ (hmr ? "": "\n");
                if ( hmr ) {
                    s+="__modimports['"+alias+"'] = "+alias+"; __initial_modimports['"+alias+"'] = true; \n";
                }
            }
        }
        s += "  };\n";
        s += "  kaddinit(_initmods); const _kimptmp = {};\n\n";
        return s;
    }

    protected String constructLibName(File f, FileResolver resolver) {
        String unique = resolver.resolveUniquePath(f);
        if (unique.startsWith("/"))
            unique = unique.substring(1);
        String name = unique;
        if ( name.endsWith(".js") )
            name = name.substring(0,name.length()-3);
        if ( name.endsWith(".jsx") )
            name = name.substring(0,name.length()-4);
        if ( name.endsWith(".json") )
            name = name.substring(0,name.length()-5);
        return name;
    }

    protected String getInitialShims() {
        return
            (globalPrologue != null ? globalPrologue+"\n" : "") +
            "// generated, see _appsrc folder in chrome's src tab for original sourcecode\n\n"+
            "window.kmodules = {};\n" +
            "\n" +
            "  function kgetModule(name) {\n" +
            "    var res = kmodules[name];\n" +
            "    if ( res == null ) {\n" +
            "      kmodules[name] = { exports: {} };\n" +
            "      return kgetModule(name);\n" +
            "    } else {\n" +
            "      return res;\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  function krequire(name) {\n" +
            "    const res = kgetModule(name).exports;\n" +
            "    return res;\n" +
            "  }\n" +
            "\n"+
            "window.__keval = window.__keval || {};\n"+
            "window.klibmap = window.klibmap || {};\nwindow.kimports = window.kimports || {};\n"+
            "window._sprd = function (obj) {\n" +
            "  const copy = Object.assign({},obj);\n" +
            "  Object.keys(obj).forEach( key => {\n" +
            "    if ( key.indexOf(\"...\") == 0 ) {\n" +
            "      const ins = obj[key];\n" +
            "      if ( typeof ins != 'undefined') {\n" +
            "        Object.keys(ins).forEach( ikey => {\n" +
            "          if ( typeof ins[ikey] != 'undefined')\n" +
            "          {\n" +
            "            obj[ikey] = ins[ikey];\n" +
            "          }\n" +
            "        });\n" +
            "        delete obj[key];\n" +
            "      }\n" +
            "    } else {\n" +
            "      obj[key] = copy[key]; // overwrite original value\n" +
            "    }\n" +
            "  });\n" +
            "  return obj;\n" +
            "};\n" +
            "var _kreporterr = false;"+
            "window._kresolve = function (libname,identifier) {\n" +
            "  var res = klibmap[libname] ? klibmap[libname]() : (window.kimports[libname] ? window.kimports[libname] : null);\n" +
            "  if ( identifier && res) res = res[identifier];\n"+
            "  if ( ! res ) {\n" +
            "    if ( !identifier)\n"+
            "        res = kmodules[libname] ? kmodules[libname].exports : null;\n" +
            "    else\n"+
            "        res = kmodules[libname] ? kmodules[libname].exports[identifier] : null;\n" +
            "  }\n" +
            "  if ( ! res ) {\n" +
            "    if (_kreporterr) console.error(\"unable to resolve \"+identifier+\" in klibmap['\"+libname+\"'] \")\n" +
            "  }\n" +
            "  else if (!identifier) {"+
            "    var res1 = res.__esModule ? res.default:res;\n" +
            "    return res1.__kdefault__ ? res1.__kdefault__ : res1;\n"+
            "  }"+
            "  return res;\n" +
            "};\n" +
            "window.module = {}; \n" +
            "const kinitfuns = [];\n"+
            "function kaddinit(fun) { fun(); kinitfuns.push(fun); }\n"+
            (dev ? "window.process = { env: {} };\n" : "window.process = { env: { 'NODE_ENV' : 'production' } };\n")+
            (dev && hmr ?
                getHMRFunWrapper()
                :
                "_kwrapfn = function(fn){\n" +
                "  return fn;\n" +
                "};\n"
            );
    }

    protected String getHMRFunWrapper() {
        return "_kwrapfn = function(fn){\n" +
        "  if ( typeof fn === 'function') {\n" +
        "    if ( ! fn._kNoHMR ) {\n" +
        "      if ( fn.toString().indexOf('class') == 0 ) {\n" +
        "        return fn; // classes are patched by prototype\n" +
        "      } else if ( fn.toString().indexOf('function') == 0 ) {\n" +
        "        const f = function(){\n" +
        "          return f._kwrapped.apply(this, arguments);\n" +
        "        };\n" +
        "        f._kwrapped = fn;\n" +
        "        return f;\n" +
        "      } else {\n" +
        "        const f = function(){\n" +
        "          return f._kwrapped.apply(this, arguments);\n" +
        "        };\n" +
        "        f._kwrapped = fn;\n" +
        "        return f;\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "  return fn;\n" +
        "};\n";
    }

    public static boolean USE_CUSTOM_RELOADFUN = false;
    protected String getHMRReloadFun() {
        if ( USE_CUSTOM_RELOADFUN ) // must be provided at bottom of index.jsx then
            return "";
        return "\nif (typeof _kHMR === 'undefined') {\n" +
            "  if (typeof KClient === 'undefined') {\n" +
            "    console.error(\"hot module reloading requires 'import {KClient} from 'kontraktor-client''\");\n" +
            "  }\n" +
            "  const hmrcl = new KClient().useProxies(false);\n" +
            "  let addr = \"ws://\" + window.location.host + \"/hotreloading\";\n" +
            "\n" +
            "  window._kredefineModule = function(patch, prev, libname, noUpdate) {\n" +
            "\n" +
            "    let impPatch = '';\n" +
            "    Object.getOwnPropertyNames(patch.__modimports).forEach( key => {\n" +
            "      if ( ! prev.__initial_modimports[key] ) {\n" +
            "        prev.__modimports[key] = patch.__modimports[key];\n" +
            "        impPatch += '\\nvar '+key+'= __modimports.'+key+';';\n" +
            "        console.log(\"new import detected:\",key);\n" +
            "\n" +
            "      }\n" +
            "    });\n" +
            "    Object.getOwnPropertyNames(patch).forEach(topleveldef=>{\n" +
            "        try {\n" +
            "          const istop = \"__kdefault__\" !== topleveldef && prev['__kdefault__'] === prev[topleveldef];\n" +
            "          if (\"__kdefault__\" === topleveldef) {// ignore\n" +
            "          } else if (!prev[topleveldef]) {\n" +
            "            prev[topleveldef] = patch[topleveldef];\n" +
            "            // new definition, FIXME: not locally visible, unsupported for now\n" +
            "            console.log('new definition detected',topleveldef);\n" +
            "          } else if (patch[topleveldef]._kNoHMR) {// unmarked for HMR\n" +
            "          } else if (typeof patch[topleveldef] === 'function') {\n" +
            "            let src = patch[topleveldef].toString();\n" +
            "            const isclass = src.indexOf(\"class\") == 0;\n" +
            "            const isfun = src.indexOf(\"function\") == 0;\n" +
            "            if (isfun || (!isclass)) // assume function or lambda\n" +
            "            {\n" +
            "              if (patch[topleveldef]._kwrapped && prev[topleveldef]._kwrapped) {\n" +
            "                let funsrc = patch[topleveldef]._kwrapped.toString();\n" +
            "                let evalSrc = impPatch+\";\"+\"\" + topleveldef + \" = \" + funsrc + \";\" + topleveldef;\n" +
            "                const newfun = __keval[libname](evalSrc);\n" +
            "                prev[topleveldef]._kwrapped = newfun;\n" +
            "              }\n" +
            "            } else if (isclass) {\n" +
            "              const newName = topleveldef;\n" +
            "              const newDef = __keval[libname](impPatch+\";\"+newName + \"=\" + src + \"; \" + newName);\n" +
            "              Object.getOwnPropertyNames(newDef.prototype).forEach(key=>{\n" +
            "                  prev[topleveldef].prototype[key] = newDef.prototype[key];\n" +
            "                }\n" +
            "              );\n" +
            "            } else {\n" +
            "              // should not happen\n" +
            "              console.error(\"unknown function object\", src);\n" +
            "            }\n" +
            "          } else {\n" +
            "            if (typeof patch[topleveldef] === 'object')\n" +
            "              Object.assign(prev[topleveldef], patch[topleveldef]);\n" +
            "            else {\n" +
            "              console.log('(possible hot rel failure) direct assignment on redefine:' + topleveldef + ',' + (typeof patch[topleveldef]), patch[topleveldef]);\n" +
            "              prev[topleveldef] = patch[topleveldef];\n" +
            "            }\n" +
            "          }\n" +
            "          if (istop)\n" +
            "            prev['__kdefault__'] = prev[topleveldef];\n" +
            "        } catch (e) {\n" +
            "          if (!(e instanceof TypeError))\n" +
            "            console.log(e);\n" +
            "        }\n" +
            "      }\n" +
            "    );\n" +
            "    !noUpdate &&  window._kreactapprender.forceUpdate();\n" +
            "  }\n" +
            "  ;\n" +
            "  // subscribe to filewatcher\n" +
            "  hmrcl.connect(addr, \"WS\").then((conn,err)=>{\n" +
            "      if (err) {\n" +
            "        console.error(\"failed to connect to hot reloading actor on '\" + addr + \"'. Hot reloading won't work.\");\n" +
            "        console.error('add to server builder:\".hmrServer(true)\"\\n');\n" +
            "        return;\n" +
            "      }\n" +
            "      conn.ask(\"addListener\", (libname,e)=>{\n" +
            "          console.log(\"a file has changed _appsrc/\" + libname);\n" +
            "          if (!window._kreactapprender) {\n" +
            "            console.error(\"hot module reloading requires window._kreactapprender to be set to rect root. E.g. 'window._kreactapprender = ReactDOM.render(global.app,document.getElementById(\\\"root\\\"));' \");\n" +
            "            return;\n" +
            "          }\n" +
            "          if (!libname) {\n" +
            "            console.error(\"failed to init hot reloading actor on '\" + addr + \"'. Hot reloading won't work.\");\n" +
            "            console.error('add to server builder:\".hmrServer(true)\"\\n');\n" +
            "          }\n" +
            "          const lib = kimports[libname];\n" +
            "          if (lib) {\n" +
            "            // fetch new source and patch\n" +
            "            fetch(\"_appsrc/\" + libname + \".transpiled\").then(response=>response.text()).then(text=>{\n" +
            "                const prev = kimports[libname];\n" +
            "                const prevEval = __keval[libname];\n" +
            "                const exp = eval(\"let _kHMR=true;\" + text.toString());\n" +
            "                const patch = kimports[libname];\n" +
            "                kimports[libname] = prev;\n" +
            "                __keval[libname] = prevEval;\n" +
            "                window._kredefineModule(patch, prev, libname);\n" +
            "              }\n" +
            "            );\n" +
            "          }\n" +
            "        }\n" +
            "      ).then((r,e)=>{\n" +
            "          if (r)\n" +
            "            console.log('connected to hmr server');\n" +
            "          else\n" +
            "            console.log('could not subscribe to hmr server');\n" +
            "        }\n" +
            "      );\n" +
            "    }\n" +
            "  );\n" +
            "\n" +
            "  // initially redefine all libs to avoid state loss on first redefine\n" +
            "  console.log(\"init hot reloading ..\");\n" +
            "  Object.getOwnPropertyNames(kimports).forEach(prop=>{\n" +
            "      window._kredefineModule(kimports[prop], kimports[prop], prop, true);\n" +
            "    }\n" +
            "  );\n" +
            "  console.log(\"... done init hot reloading\");\n" +
            "}\n";
    }

    public JSXIntrinsicTranspiler nodeModulesDir(File jnpmNodeModulesDir) {
        this.jnpmNodeModulesDir = jnpmNodeModulesDir;
        return this;
    }

    public JSXIntrinsicTranspiler configureJNPM(String nodeModulesDir, String pathToJNPMConfigKsonFile) {
        this.jnpmNodeModulesDir = new File(nodeModulesDir);
        this.jnpmConfigFile = pathToJNPMConfigKsonFile;
        return this;
    }

    public JSXIntrinsicTranspiler configureJNPM(String nodeModulesDir, JNPMConfig config) {
        this.jnpmNodeModulesDir = new File(nodeModulesDir);
        this.jnpmConfig = config;
        return this;
    }

    /**
     * automatically import unknown modules via jnpm
     *
     * @param b
     * @return
     */
    public JSXIntrinsicTranspiler autoJNPM(boolean b) {
        this.autoJNPM = b;
        return this;
    }

    public JSXIntrinsicTranspiler dev(boolean dev) {
        this.dev = dev;
        return this;
    }

    public JSXIntrinsicTranspiler jnpmNodeModulesDir(File jnpmNodeModulesDir) {
        this.jnpmNodeModulesDir = jnpmNodeModulesDir;
        return this;
    }

    public JSXIntrinsicTranspiler jnpmConfig(JNPMConfig jnpmConfig) {
        this.jnpmConfig = jnpmConfig;
        return this;
    }

    public JSXIntrinsicTranspiler jnpmConfigFile(String jnpmConfigFile) {
        this.jnpmConfigFile = jnpmConfigFile;
        return this;
    }

    public JSXIntrinsicTranspiler jnpmConfigFileCached(JNPMConfig jnpmConfigFileCached) {
        this.jnpmConfigFileCached = jnpmConfigFileCached;
        return this;
    }

    public JSXIntrinsicTranspiler readFiles(List<WatchedFile> readFiles) {
        this.readFiles = readFiles;
        return this;
    }

    public JSXIntrinsicTranspiler watcher(FileWatcher watcher) {
        this.watcher = watcher;
        return this;
    }

    /**
     * enable hot module reloading, requires exposure of FileWatcher as a json websocket actor like:
     *
     * .websocket("/hotreloading", FileWatcher.get())
     *  .serType(SerializerType.JsonNoRef)
     *  .buildWebsocket()
     *
     * @param hmr
     * @return
     */
    public JSXIntrinsicTranspiler hmr(boolean hmr) {
        this.hmr = hmr;
        return this;
    }

    public JSXIntrinsicTranspiler falseFile(File falseFile) {
        this.falseFile = falseFile;
        return this;
    }
}
