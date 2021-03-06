package ru.dpohvar.varscript.workspace;

import groovy.lang.*;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import ru.dpohvar.varscript.VarScript;
import ru.dpohvar.varscript.caller.Caller;
import ru.dpohvar.varscript.caller.FlushTask;
import ru.dpohvar.varscript.utils.PropertySelector;
import ru.dpohvar.varscript.utils.ScriptProperties;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public abstract class CallerScript extends Script implements ScriptProperties {

    private Caller caller;
    private Workspace workspace;
    private BukkitScheduler scheduler;
    private VarScript plugin;
    private StringBuilder printCache = new StringBuilder();
    private BukkitTask flushBukkitTask = null;
    private Server server;
    private static List<GroovyObject> dynamicModifiers = new ArrayList<GroovyObject>();

    public static List<GroovyObject> getDynamicModifiers() {
        return dynamicModifiers;
    }

    public CallerScript initializeScript(Workspace workspace, Caller caller, Binding binding){
        if (this.workspace != null) throw new IllegalStateException("Script has already been initialized");
        this.workspace = workspace;
        this.plugin = workspace.getWorkspaceService().getVarScript();
        server = plugin.getServer();
        this.scheduler = server.getScheduler();
        if (caller != null) this.caller = caller;
        else this.caller = plugin.getCallerService().getCaller(server.getConsoleSender());
        if (binding == null) binding = new Binding();
        setBinding(binding);
        return this;
    }

    @Override
    public Caller getCaller() {
        return caller;
    }

    @Override
    public Workspace getWorkspace() {
        return workspace;
    }

    @Override
    public Object getMe(){
        CommandSender sender = caller.getSender();
        if (sender instanceof BlockCommandSender) return ((BlockCommandSender) sender).getBlock();
        else return sender;
    }

    @Override
    public Object get_(){
        return caller.getLastResult();
    }

    @Override
    public Server getServer(){
        return server;
    }

    @Override
    public WorkspaceService getGlobal(){
        return workspace.getWorkspaceService();
    }

    @Override
    public void println() {
        println("");
    }

    @Override
    public void print(Object value) {
        String chars = DefaultGroovyMethods.toString(value);
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c == '\n') {
                caller.sendPrintMessage(printCache.toString(), workspace.getName());
                printCache = new StringBuilder();
            } else {
                printCache.append(c);
            }
        }
        if (flushBukkitTask == null) flushBukkitTask = scheduler.runTask(plugin, new FlushTask(this));
    }

    @Override
    public void println(Object value) {
        String val = DefaultGroovyMethods.toString(value);
        if (printCache.length() > 0) val = printCache.toString() + val;
        caller.sendPrintMessage(val, workspace.getName());
    }

    @Override
    public void printf(String format, Object value) {
        String result = String.format(format, value);
        print(result);
    }

    @Override
    public void printf(String format, Object[] values) {
        String result = String.format(format, values);
        print(result);
    }

    public void broadcast(Object message){
        server.broadcastMessage(DefaultGroovyMethods.toString(message));
    }

    public synchronized void flush(){
        if (printCache.length() > 0) caller.sendPrintMessage(printCache.toString(), workspace.getName());
        flushBukkitTask = null;
        printCache = new StringBuilder();
    }

    private File getScriptFile(String name){
        File scriptsDirectory = workspace.getWorkspaceService().getScriptsDirectory();
        return new File(scriptsDirectory, name + ".groovy");
    }

    public Object runScriptFile(String name, Object... args) throws Exception {
        return runScriptFile(getScriptFile(name), args);
    }

    public Object runScriptFile(String name, List args) throws Exception {
        return runScriptFile(getScriptFile(name), args);
    }

    public Object runScriptFile(String name, Binding binding) throws Exception {
        return runScriptFile(getScriptFile(name), binding);
    }

    public Object runScriptFile(String name, Map variables) throws Exception {
        return runScriptFile(getScriptFile(name), variables);
    }

    public Object runScriptFile(File file, Binding binding) throws Exception {
        if (file.isFile()) {
            return workspace.executeScript(caller, file, binding);
        } else {
            throw new FileNotFoundException(file.toString());
        }
    }

    public Object runScriptFile(File file, Map variables) throws Exception {
        return runScriptFile(file, new Binding(variables));
    }

    public Object runScriptFile(File file, Object... args) throws Exception {
        Binding binding = new Binding();
        binding.setVariable("args", DefaultGroovyMethods.asType(args, List.class));
        return runScriptFile(file, binding);
    }

    public Object runScriptFile(File file, List args) throws Exception {
        Binding binding = new Binding();
        binding.setVariable("args", new ArrayList(args));
        return runScriptFile(file, binding);
    }

    @Override
    public Object invokeMethod(String name, Object args){
        Object[] arguments;
        if (args instanceof Object[]) arguments = (Object[]) args;
        else arguments = new Object[]{args};

        // SELF METHOD
        try {
            return getMetaClass().invokeMethod(this, name, arguments);
        } catch (MissingMethodException ignored) {}

        // WORKSPACE METHOD
        try {
            return workspace.getMetaClass().invokeMethod(workspace, name, arguments);
        } catch (MissingMethodException ignored) {}

        // SELF CLOSURE
        Object property;
        try {
            property = getBinding().getVariable(name);
            if (property != null) try {
                return callProperty(property, arguments);
            } catch (MissingMethodException ignored) {}
        } catch (MissingPropertyException ignored) {}

        // DYNAMIC METHOD
        try {
            return invokeMethodFor(this, name, arguments);
        } catch (PropertySelector ignored) {}

        // WORKSPACE CLOSURE
        try {
            property = workspace.getBinding().getVariable(name);
            if (property != null) try {
                return callProperty(property, arguments);
            } catch (MissingMethodException ignored) {}
        } catch (MissingPropertyException ignored) {}

        // GLOBAL CLOSURE
        try {
            property = workspace.getWorkspaceService().getBinding().getVariable(name);
            if (property != null) try {
                return callProperty(property, arguments);
            } catch (MissingMethodException ignored) {}
        } catch (MissingPropertyException ignored) {}

        throw new MissingMethodException(name, this.getClass(), arguments);
    }

    private Object callProperty(Object property, Object... arguments){
        if (property instanceof Closure) return ((Closure) property).call(arguments);
        else return InvokerHelper.invokeMethod(property,"call", arguments);
    }

    @Override
    public Object getProperty(String property) {
        // SELF PROPERTY
        try {
            return getMetaClass().getProperty(this, property);
        } catch (MissingPropertyException ignored) {}

        // SELF BINDING
        try {
            return getBinding().getVariable(property);
        } catch (MissingPropertyException ignored) {}

        // WORKSPACE BINDING
        try {
            return workspace.getBinding().getVariable(property);
        } catch (MissingPropertyException ignored) {}

        // SERVICE BINDING
        try {
            return workspace.getWorkspaceService().getBinding().getVariable(property);
        } catch (MissingPropertyException ignored) {}

        // SELF DYNAMIC PROPERTY
        try {
           return getPropertyFor(this, property);
        } catch (PropertySelector ignored) {}

        throw new MissingPropertyException(property, this.getClass());
    }

    @Override
    public void setProperty(String property, Object newValue) {
        // SELF PROPERTY
        try {
            getMetaClass().setProperty(this, property, newValue);
        } catch (MissingPropertyException ignored) {}

        // WORKSPACE PROPERTY
        try {
            workspace.getMetaClass().setProperty(workspace, property, newValue);
        } catch (MissingPropertyException ignored) {}

        // SERVICE PROPERTY
        try {
            WorkspaceService service = workspace.getWorkspaceService();
            service.getMetaClass().setProperty(service, property, newValue);
        } catch (MissingPropertyException ignored) {}

        // WORKSPACE BINDING
        workspace.getBinding().setVariable(property, newValue);
    }

    public static Object getPropertyFor(ScriptProperties script, String property){
        Object args = new Object[]{script, property};
        for (GroovyObject modifier : dynamicModifiers) try {
            return modifier.getMetaClass().invokeMethod(modifier, "getPropertyFor", args);
        } catch (InvokerInvocationException exception){
            if (exception.getCause() != PropertySelector.next) throw exception;
        } catch (MissingMethodException ignored){}
        throw PropertySelector.next;
    }

    public static Object invokeMethodFor(ScriptProperties script, String name, Object[] args){
        Object arguments = new Object[]{script, name, args};
        for (GroovyObject modifier : dynamicModifiers) try {
            return InvokerHelper.invokeMethod(modifier, "invokeMethodFor", arguments);
        } catch (InvokerInvocationException exception){
            if (exception.getCause() != PropertySelector.next) throw exception;
        } catch (MissingMethodException ignored){}
        throw PropertySelector.next;
    }

    @Override
    public String toString(){
        return caller.getSender().getName() + "@" + workspace;
    }
}
